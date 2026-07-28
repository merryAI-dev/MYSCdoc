#!/bin/bash
# 야간 D — Nested Learning 차용 실험: slow/fast 어댑터 스택 (CMS의 동결모델 구현)
# night_a2(재학습)가 끝나면 GPU0에서 시작한다.
set -e
cd /data/tta/mydoc-kg
export PATH="/data/tta/mydoc-kg/env/bin:$PATH"

until grep -q "NIGHT_A2_DONE" runs/night_a2.log 2>/dev/null; do sleep 30; done
echo "[D0] 재학습 종료 감지 — CMS 실험 시작"

cd scripts
python build_cms_data.py
echo "[D1] slow 어댑터 학습 (과거 80문서)"
CUDA_VISIBLE_DEVICES=0 python train_exaone_sft.py \
  --model /data/tta/EXAONE/exaone-4.0-1.2B-local \
  --data-dir ../corpus/cms_slow --eval-file dev.jsonl \
  --out ../runs/cms_slow --epochs 3 --max-seq-len 6144
echo "[D2] slow 병합"
CUDA_VISIBLE_DEVICES=0 python merge_and_export.py \
  --base /data/tta/EXAONE/exaone-4.0-1.2B-local \
  --adapter ../runs/cms_slow/best --out ../runs/cms_slow_merged
echo "[D3] fast 어댑터 학습 (최근 문서, rank4·2ep — 병합된 slow 위에)"
CUDA_VISIBLE_DEVICES=0 python train_exaone_sft.py \
  --model ../runs/cms_slow_merged \
  --data-dir ../corpus/cms_fast --eval-file dev.jsonl \
  --out ../runs/cms_fast --epochs 2 --lr 1e-4 --lora-r 4 --max-seq-len 6144
echo "[D4] 4칸 손실표"
CUDA_VISIBLE_DEVICES=0 python eval_loss.py --model ../runs/cms_slow_merged \
  --data ../corpus/cms_eval/dev_old.jsonl ../corpus/cms_eval/dev_recent.jsonl
CUDA_VISIBLE_DEVICES=0 python eval_loss.py --model ../runs/cms_slow_merged \
  --adapter ../runs/cms_fast/best \
  --data ../corpus/cms_eval/dev_old.jsonl ../corpus/cms_eval/dev_recent.jsonl
echo "NIGHT_D_DONE"
