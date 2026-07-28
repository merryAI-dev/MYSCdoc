#!/usr/bin/env bash
set -euo pipefail

BASE=/data/tta/EXAONE/exaone-4.0-1.2B-local
JUDGE=/data/tta/EXAONE/exaone-4.0.1-32B-local
CONTROL=runs/chat_sft_v4_control_seed42
H1=runs/chat_sft_v4_h1_seed42
EVAL=research/runs/chat_selection_v4.jsonl

python -c 'import json,sys
paths=sys.argv[1:]
runs=[json.load(open(p+"/run_config.json")) for p in paths]
assert all(r["status"]=="completed" and r["global_step"]==342 for r in runs)
assert all(sum(1 for _ in __import__("pathlib").Path(p).glob("checkpoint-*"))==6 for p in paths)
print("TRAINING_ARTIFACTS_OK")' "$CONTROL" "$H1"

for out in runs/v4_seed42_control_answers.json runs/v4_seed42_h1_answers.json \
           runs/v4_seed42_judged.json runs/v4_seed42_selection_report.json; do
  test ! -e "$out" || { echo "refusing to overwrite $out" >&2; exit 1; }
done

CUDA_VISIBLE_DEVICES=0 python scripts/select_chat_checkpoint_vllm.py \
  --base "$BASE" --ckpt-root "$CONTROL" --eval "$EVAL" \
  --prefix control --out runs/v4_seed42_control_answers.json &
control_pid=$!
CUDA_VISIBLE_DEVICES=1 python scripts/select_chat_checkpoint_vllm.py \
  --base "$BASE" --ckpt-root "$H1" --eval "$EVAL" \
  --prefix h1 --no-base --out runs/v4_seed42_h1_answers.json &
h1_pid=$!
wait "$control_pid" "$h1_pid"

CUDA_VISIBLE_DEVICES=0,1 python research/judge_answers.py \
  --model "$JUDGE" --tp 2 \
  --answers runs/v4_seed42_control_answers.json runs/v4_seed42_h1_answers.json \
  --out runs/v4_seed42_judged.json

python research/select_operating_point.py \
  --judged runs/v4_seed42_judged.json --names seed42 \
  --alpha 0.10 --delta 0.05 --out runs/v4_seed42_selection_report.json

python research/build_judge_calibration_review.py \
  --judged runs/v4_seed42_judged.json \
  --html-out runs/v4_judge_calibration_review.html \
  --key-out runs/v4_judge_calibration_key.json --n 100
