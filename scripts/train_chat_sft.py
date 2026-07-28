#!/usr/bin/env python3
"""
챗 답변 SFT — 근거 없으면 거절하고, 결정의 확정도를 구분해 말하도록 가르친다.

추출 증류(train_exaone_sft.py)와 목적이 달라 스크립트를 분리했다. 그쪽은 "JSON 스키마를
정확히 뱉기"(지식 주입), 여기는 "행동을 바꾸기"다. 좋은 체크포인트의 정의부터 다르다.

── 왜 이 과제가 학습으로 풀리는가 ──────────────────────────────────────────
순정 1.2B는 근거 없는 질문 6건 중 4건을 지어냈다(무관한 결정을 질문 주제로 갈아끼우고
실명까지 붙였다). 이건 모델 크기 문제가 아니다 — Qwen2.5-7B-Instruct 순정도 거절률 0.30이다
(Hallucination Tax of RFT, arXiv:2505.13988). 학습으로 메우는 것이 정석이며
OCC-RAG(arXiv:2606.00683)는 0.6B에서 거절 정확도 6.3%→86.9%를 보고했고, 튜닝 후에는
0.6B와 1.7B 차이가 0.3pt였다 — 1.2B는 용량이 충분하다.

확정도(확정/조건부/예정/논의)는 프롬프트 라벨만으로는 안 따른다. 32B조차 라벨만 줬을 때
반영률이 66%였고, "왜 그렇게 보는지 설명하라"로 바꾸자 99%가 됐다. 그 설명이 담긴 답변을
학습 타깃으로 쓴다 — CAG(arXiv:2404.06809)의 credibility-guided 방식과 같은 처방이다.

── 체크포인트 선택이 이 스크립트의 핵심 ─────────────────────────────────
eval_loss로 고르면 안 된다. dev의 70%가 '답 있는' 사례라 loss는 그 다수에 지배되고, 정작
배우려는 거절 능력은 반영되지 않는다. LIMA(arXiv:2305.11206)의 관찰이 근거다 —
"perplexity does not correlate with generation quality". LIMA도 1,000샘플을 15에폭 돌린 뒤
5~10에폭 구간에서 50건짜리 dev로 체크포인트를 손으로 골랐다.

그래서 여기서는 에폭마다 전부 저장만 하고, 학습 후 select_chat_checkpoint.py가 실제 생성으로
Honesty Score를 재서 고른다. 지표는 자작이 아니라 Alignment for Honesty(NeurIPS 2024)의 정의:
    Honesty = ½ × (Prudence + (1 − Over-Conservativeness))
    Prudence              = 근거 없을 때 거절한 비율
    Over-Conservativeness = 근거 있는데 거절한 비율
한쪽만 보면 "전부 거절"하는 모델이 최고점을 받는다 — 문헌이 명시적으로 경고하는 실패 모드다.

── 하이퍼파라미터 근거 ──────────────────────────────────────────────────
lr 2e-4  : 1B급 공개 config의 실측 범위(torchtune Llama-3.2-1B 3e-4, alignment-handbook 2e-4).
           LoRA는 full FT보다 10배 이상 높은 LR을 쓰며, 짧은 런일수록 더 그렇다.
epochs 6 : LIMA식으로 loss 최적점을 지나서 돌리고 행동 지표로 고른다. 6은 LIMA의 선택 구간
           (5~10) 안이다. 단독으로 마지막 체크포인트를 쓰면 안 된다.
r=16     : 320샘플이 담은 정보량에 비해 이미 과분한 용량이다. 저랭크는 도메인 외 능력 보존에
           유리하다(LoRA Learns Less and Forgets Less, TMLR 2024) — 행동만 바꾸려는 우리에겐
           오히려 장점이다.
dropout 0.1 : 소규모 데이터 과적합 완화. 320샘플 × 6에폭이면 상단값이 맞다.
target_modules 7개 : 어텐션만 걸면 같은 파라미터 수에서도 성능이 낮다는 보고가 일관된다.

사용:
  CUDA_VISIBLE_DEVICES=0 python train_chat_sft.py \
      --model /data/tta/EXAONE/exaone-4.0-1.2B-local \
      --data-dir corpus/chat_sft_v2 --out runs/chat_sft_v2
"""
import argparse
import json
import os
import subprocess
import sys
from collections import Counter

import torch
import transformers
from datasets import load_dataset
from peft import LoraConfig
from transformers import AutoTokenizer, set_seed
from trl import SFTConfig, SFTTrainer

# 학습 loss가 이 아래로 내려가면 암기 신호로 본다 (커뮤니티 경험칙).
OVERFIT_LOSS_HINT = 0.2


def dataset_stats(path, tokenizer, max_len, label_path=None):
    """공개 저장소 관행 — 데이터가 어떻게 생겼는지, 잘리는 게 있는지 남긴다.

    프롬프트에 검색 결과가 들어가는 구조라 조용한 절단이 가장 위험하다. 잘리면 completion이
    통째로 날아가고 모델은 '멈추는 법'을 못 배운다(추출 학습에서 실제로 겪은 실패).
    """
    lens, truncated, empty_completion = [], 0, 0
    for line in open(path):
        row = json.loads(line)
        full = tokenizer.apply_chat_template(row["prompt"] + row["completion"], tokenize=False)
        n = len(tokenizer(full).input_ids)
        lens.append(n)
        truncated += n > max_len
        if not row["completion"][0]["content"].strip():
            empty_completion += 1
    lens.sort()
    stats = {
        "n": len(lens),
        "tokens_median": lens[len(lens) // 2] if lens else 0,
        "tokens_p95": lens[int(len(lens) * 0.95)] if lens else 0,
        "tokens_max": lens[-1] if lens else 0,
        "truncated_at_max_len": truncated,
        "empty_completion": empty_completion,
    }
    if label_path and os.path.exists(label_path):
        labels = Counter(json.loads(l).get("label") for l in open(label_path))
        stats["labels"] = dict(labels)
    return stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--data-dir", required=True, help="train.jsonl + dev.jsonl")
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=float, default=6)
    ap.add_argument("--lr", type=float, default=2e-4)
    # 실측: v2 학습셋 최대 2,647토큰. 8192면 절단 0건.
    ap.add_argument("--max-seq-len", type=int, default=8192)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--lora-dropout", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    set_seed(args.seed)
    os.makedirs(args.out, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)

    # ── 데이터 점검을 먼저 한다. 절단이 있으면 학습을 시작하지 않는다. ──
    stats = {
        "train": dataset_stats(f"{args.data_dir}/train.jsonl", tokenizer, args.max_seq_len),
        "dev": dataset_stats(f"{args.data_dir}/dev.jsonl", tokenizer, args.max_seq_len),
    }
    print("[data]", json.dumps(stats, ensure_ascii=False))
    bad = [k for k, v in stats.items() if v["truncated_at_max_len"] or v["empty_completion"]]
    if bad:
        sys.exit(f"[중단] {bad}에 절단 또는 빈 답변이 있다 — max_seq_len을 올리거나 데이터를 고칠 것")

    data = load_dataset("json", data_files={
        "train": f"{args.data_dir}/train.jsonl",
        "dev": f"{args.data_dir}/dev.jsonl",
    })

    peft_config = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_r * 2,   # α/r = 2. LR과 곱해져 실효 학습률을 키운다.
        lora_dropout=args.lora_dropout,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                        "gate_proj", "up_proj", "down_proj"],
        bias="none",
        task_type="CAUSAL_LM",
    )

    config = SFTConfig(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_ratio=0.1,
        per_device_train_batch_size=2,
        gradient_accumulation_steps=4,
        max_length=args.max_seq_len,
        packing=False,
        # prompt/completion 형식의 기본값이지만 명시한다 — 사실 목록(프롬프트)을 외우게 하지 않는다.
        completion_only_loss=True,
        bf16=True,
        logging_steps=5,
        eval_strategy="epoch",
        # 전부 남긴다. best는 학습 후 행동 지표로 고른다(eval_loss로 고르지 않는다).
        save_strategy="epoch",
        save_total_limit=None,
        load_best_model_at_end=False,
        report_to=[],
        seed=args.seed,
        data_seed=args.seed,
    )

    # ── 재현용 설정 덤프 — 공개 저장소 관행 ──
    try:
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"],
                                         stderr=subprocess.DEVNULL).decode().strip()
    except Exception:
        commit = "unknown"
    json.dump({
        "args": vars(args), "git_commit": commit,
        "versions": {"torch": torch.__version__, "transformers": transformers.__version__},
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu",
        "dataset_stats": stats,
        "lora": {"r": args.lora_r, "alpha": args.lora_r * 2, "dropout": args.lora_dropout},
    }, open(f"{args.out}/run_config.json", "w"), ensure_ascii=False, indent=1)

    trainer = SFTTrainer(
        model=args.model, args=config,
        train_dataset=data["train"], eval_dataset=data["dev"],
        peft_config=peft_config,
    )
    result = trainer.train()

    final_loss = result.training_loss
    print(f"[done] train_loss={final_loss:.4f} · 체크포인트 전부 저장 → {args.out}")
    if final_loss < OVERFIT_LOSS_HINT:
        print(f"[경고] train_loss가 {OVERFIT_LOSS_HINT} 아래다 — 암기 가능성. "
              f"뒤쪽 체크포인트가 행동 평가에서 탈락하는지 확인할 것")
    print("       best 선택은 select_chat_checkpoint.py로 (Honesty Score 기준)")


if __name__ == "__main__":
    main()
