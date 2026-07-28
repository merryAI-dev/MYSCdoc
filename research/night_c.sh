#!/bin/bash
# 야간 C 병렬판: GPU1 전용, 32B 단일 GPU(tp=1), 캘리브레이션+본판정 통합 1회 로드.
set -e
cd /data/tta/mydoc-kg
export PATH="/data/tta/mydoc-kg/env/bin:$PATH"

until grep -q "\[A1\] 완료" runs/night_a.log 2>/dev/null; do sleep 30; done
echo "[C0] GPU1 단독 판정 시작 (tp=1, max_model_len 10240)"

cd scripts
cat judge_calib.jsonl judge_jobs.jsonl > judge_all.jsonl
CUDA_VISIBLE_DEVICES=1 python judge_weights.py \
  --model /data/tta/EXAONE/exaone-4.0.1-32B-local \
  --jobs judge_all.jsonl --out ../runs/weights_all.jsonl \
  --tp 1 --max-model-len 10240
echo "NIGHT_C_DONE"
