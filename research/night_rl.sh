#!/bin/bash
# 야간 RL v4: colocate 모드 — 별도 vLLM 서버 없이 GPU0 하나에서 학습+rollout
set -e
cd /data/tta/mydoc-kg/rl
export PATH="/data/tta/mydoc-kg/env_rl/bin:$PATH"
MODEL=/data/tta/EXAONE/exaone-4.0-1.2B-local

echo "[RL0] colocate 학습 시작"
CUDA_VISIBLE_DEVICES=0 python grpo_train_search.py --model $MODEL \
  --train train.jsonl --corpus rl_corpus.jsonl --out runs_grpo --max-steps 500
echo "[RL1] 학습 종료 — 체크포인트 평가"

CUDA_VISIBLE_DEVICES=0 python eval_rl_checkpoints.py --base $MODEL \
  --ckpt-root runs_grpo --val val.jsonl --corpus rl_corpus.jsonl \
  > eval_results.txt 2>&1
tail -30 eval_results.txt
echo "NIGHT_RL_DONE"
