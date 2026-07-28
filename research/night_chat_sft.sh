#!/bin/bash
# 챗 SFT: v1(라벨만) · v2(설명+어려운거절) 두 판 학습 → 병합 → 평가
set -e
cd /data/tta/mydoc-kg
export PATH="/data/tta/mydoc-kg/env/bin:$PATH"
BASE=/data/tta/EXAONE/exaone-4.0-1.2B-local

for V in v1 v2; do
  echo "[C-$V] 학습"
  CUDA_VISIBLE_DEVICES=0 python scripts/train_exaone_sft.py --model $BASE \
    --data-dir corpus/chat_sft_$V --eval-file dev.jsonl \
    --out runs/chat_sft_$V --epochs 3 --max-seq-len 8192
  echo "[C-$V] 병합"
  CUDA_VISIBLE_DEVICES=0 python scripts/merge_and_export.py --base $BASE \
    --adapter runs/chat_sft_$V/best --out runs/chat_merged_$V
done

cd scripts
echo "[C-EVAL-A] 공정 대조 (공통 55건, 각자 프롬프트)"
CUDA_VISIBLE_DEVICES=0 python eval_chat_models.py \
  --models base=$BASE v1=../runs/chat_merged_v1 --eval chat_eval_v1.jsonl \
  --out ../runs/chat_eval_A_v1.json
CUDA_VISIBLE_DEVICES=0 python eval_chat_models.py \
  --models v2=../runs/chat_merged_v2 --eval chat_eval_v2.jsonl \
  --out ../runs/chat_eval_A_v2.json
echo "[C-EVAL-B] v2 실전 (어려운 거절 포함 66건)"
CUDA_VISIBLE_DEVICES=0 python eval_chat_models.py \
  --models base=$BASE v2=../runs/chat_merged_v2 --eval chat_eval.jsonl \
  --out ../runs/chat_eval_B.json
echo "CHAT_SFT_DONE"
