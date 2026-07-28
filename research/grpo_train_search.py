#!/usr/bin/env python3
"""
Phase 1 — 단일턴 쿼리 재작성 GRPO (가설 H7·H8·H13·H14·H15).

설계 근거 (docs/RESEARCH-search-rl.md):
  - 단일턴 재작성은 1.2B에서 성공 확률이 가장 높은 형태 (DeepRetrieval: 3B PPO로 GPT-4o의
    3~10배 recall). 다중턴은 발표된 최소 성공이 3B이고 소형 코퍼스 붕괴 전례가 있어 보류.
  - 보상: 계단식 순위 보상 (이진 hit는 그룹 내 분산 0 → advantage 0 → 학습 정지)
    top1=5, ≤3위=4, ≤10위=3, ≤30위=1, 실패=−2 (DeepRetrieval의 강한 음수 바닥)
  - 형식 게이트: 위반 시 −4로 과제 보상 대체 (≤3B는 전원 필요; 곱셈식이 해킹에 안전)
  - lr 1e-6 · KL β=0.001 (KL=0은 R1-Searcher에서 붕괴) · temp 1.0 (그룹 분산 필수)
  - 25스텝마다 저장 → 붕괴가 기본값이므로 사후 best-checkpoint 선택 (ReZero 교훈)

실행 (night_rl.sh가 vLLM 서버를 GPU1에 먼저 띄운다):
  CUDA_VISIBLE_DEVICES=0 python grpo_train_search.py --model <1.2B> \
      --train rl/train.jsonl --corpus rl/rl_corpus.jsonl --out runs/grpo_search
"""
import argparse
import json
import re

from datasets import Dataset
from rank_bm25 import BM25Okapi
from trl import GRPOConfig, GRPOTrainer

from kiwipiepy import Kiwi

CONTENT = ("NNG", "NNP", "NNB", "NR", "SL", "SN", "VV", "VA", "XR")
_kiwi = Kiwi()


def toks(text):
    return [m.form for m in _kiwi.tokenize(text or "") if m.tag in CONTENT]


SYSTEM = ("사내 회의록 지식그래프를 BM25로 검색한다. 질문을 검색에 효과적인 "
          "핵심 키워드 검색어 한 줄로 바꿔라. 검색어만 출력하고 다른 말은 하지 마라.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--train", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-steps", type=int, default=500)
    args = ap.parse_args()

    corpus = [json.loads(l) for l in open(args.corpus)]
    bm25 = BM25Okapi([toks(c["text"]) for c in corpus])

    rows = [json.loads(l) for l in open(args.train)]
    data = Dataset.from_list([{
        "prompt": [{"role": "system", "content": SYSTEM},
                   {"role": "user", "content": r["question"]}],
        "gold": r["gold"],
    } for r in rows])

    def reward_search(completions, gold, **kwargs):
        rewards = []
        for comp, g in zip(completions, gold):
            text = comp[-1]["content"] if isinstance(comp, list) else str(comp)
            text = text.strip()
            # 형식 게이트: 한 줄, 2~80자, 사고 블록 금지 — 위반이면 과제 보상 없이 −4
            if (not text or "\n" in text or len(text) > 80
                    or re.search(r"<think|답변|검색어[:：]", text)):
                rewards.append(-4.0)
                continue
            q = toks(text)
            if not q:
                rewards.append(-4.0)
                continue
            scores = bm25.get_scores(q)
            order = sorted(range(len(corpus)), key=lambda i: -scores[i])[:30]
            gset = set(g)
            rank = next((r for r, i in enumerate(order, 1)
                         if scores[i] > 0 and i in gset), None)
            if rank == 1:
                rewards.append(5.0)
            elif rank and rank <= 3:
                rewards.append(4.0)
            elif rank and rank <= 10:
                rewards.append(3.0)
            elif rank and rank <= 30:
                rewards.append(1.0)
            else:
                rewards.append(-2.0)
        return rewards

    # colocate: 학습 프로세스 안에서 vLLM 구동. server 모드는 가중치 동기화 NCCL 초기화가
    # cuda/cuda:0 표기 불일치로 죽는 조합(trl 1.9.1 + vllm 0.25.1)이라 우회한다.
    config = GRPOConfig(
        output_dir=args.out,
        use_vllm=True, vllm_mode="colocate",
        vllm_gpu_memory_utilization=0.35,
        num_generations=8,
        per_device_train_batch_size=8,
        gradient_accumulation_steps=4,
        max_completion_length=96,
        learning_rate=1e-6, beta=0.001, temperature=1.0,
        max_steps=args.max_steps,
        save_steps=25, save_total_limit=25,
        logging_steps=5, bf16=True, report_to=[],
        seed=42,
    )
    trainer = GRPOTrainer(model=args.model, args=config,
                          train_dataset=data, reward_funcs=reward_search)
    trainer.train()
    print("[done] GRPO 학습 종료")


if __name__ == "__main__":
    main()
