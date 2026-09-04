#!/usr/bin/env python3
"""
M19-1: EXAONE 4.0-1.2B SFT — Gemini(교사) 추출 산출물을 LoRA로 증류.

스택 결정: TRL SFTTrainer + PEFT LoRA. Unsloth는 Exaone4ForCausalLM 미지원
(카탈로그 부재 + unslothai/unsloth#3015 미해결 종료). EXAONE 4는 transformers>=4.54에
Llama형 모듈명(q/k/v/o_proj, gate/up/down_proj)으로 정식 편입돼 있어 표준 경로면 충분하다.
1.2B × 180샘플 × 수 epoch은 H100 한 장으로 몇 분 — DDP 불필요, 단일 GPU로 돌린다.

소규모(≈180건) 데이터 원칙:
  - completion-only loss (prompt/completion 형식이면 TRL 기본값) — JSON에만 gradient 집중
  - packing 끔, NEFTune 안 씀 (구조화 출력엔 역효과)
  - epoch마다 val loss 평가 + best 체크포인트 저장 — 과적합 조기 감지
  - LoRA r=16이면 충분 (base 동결이 망각 방지의 핵심)

max_seq_len=20480: 첫 시도 때 8192로 잘랐다가 학습셋 160건 중 12건(최대 18,933 토큰)의
completion(JSON)이 EOS 없이 잘렸고, 그 결과 모델이 긴 문서에서 생성을 멈출 줄 몰라
max_new_tokens까지 계속 뽑아내는 걸 val 세트 실측(20건 중 8건 truncation)으로 확인했다.
전체 학습 분포를 덮도록 올린 값 — 다시 잘리면 같은 증상이 재발한다.

사용 (GPU 서버, mydoc-kg env):
  CUDA_VISIBLE_DEVICES=0 env/bin/python scripts/train_exaone_sft.py \
      --model /data/tta/EXAONE/exaone-4.0.1-1.2B-local \
      --data-dir corpus/sft --out runs/sft_exaone12b_lora

학습 후 병합·GGUF 변환(로컬 서빙용)은 merge_and_export.py 참고.
"""
import argparse

from datasets import load_dataset
from peft import LoraConfig
from trl import SFTConfig, SFTTrainer


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--data-dir", required=True, help="train.jsonl + 평가셋이 있는 디렉터리")
    # 청크 증류 데이터는 dev.jsonl을 쓴다 — 최종 평가용 val 20문서와 이름부터 구분해
    # 체크포인트 선택에 평가셋이 섞이지 않게 한다.
    ap.add_argument("--eval-file", default="val.jsonl")
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=float, default=4)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--max-seq-len", type=int, default=20480)
    ap.add_argument("--lora-r", type=int, default=16)
    args = ap.parse_args()

    data = load_dataset("json", data_files={
        "train": f"{args.data_dir}/train.jsonl",
        "val": f"{args.data_dir}/{args.eval_file}",
    })
    data = data.remove_columns(["doc_id"])

    peft_config = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_r * 2,
        lora_dropout=0.05,
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
        warmup_ratio=0.05,
        per_device_train_batch_size=2,
        gradient_accumulation_steps=4,   # 유효 배치 8 — 소규모 데이터는 작게
        max_length=args.max_seq_len,
        packing=False,
        bf16=True,
        logging_steps=5,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        report_to=[],
        seed=42,
    )

    trainer = SFTTrainer(
        model=args.model,
        args=config,
        train_dataset=data["train"],
        eval_dataset=data["val"],
        peft_config=peft_config,
    )
    trainer.train()
    trainer.save_model(f"{args.out}/best")
    print(f"[done] best adapter → {args.out}/best")


if __name__ == "__main__":
    main()
