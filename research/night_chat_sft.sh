#!/bin/bash
# 챗 SFT: v1(라벨만) · v2(설명+어려운거절) 두 판 학습 → 체크포인트 선택 → 병합
#
# 이전 판은 세 가지가 틀려 있었다(독립 감사에서 적발, 2026-07-28):
#  1. train_chat_sft.py가 아니라 train_exaone_sft.py를 불렀다. 그쪽은
#     load_best_model_at_end=True 라 **eval_loss로 체크포인트를 고르고**,
#     save_total_limit=2 라 나머지 에폭을 지워 버린다. 설계 문서가 loss 선택을
#     쓰지 말자고 논증한 바로 그 실패를 실행하고 있었다.
#  2. eval_chat_models.py에 모델 두 개를 한 프로세스로 넘겼다. vLLM 엔진은 del로
#     안 죽어서 두 번째 LLM()이 OOM 난다. 공용 GPU라 남의 작업까지 막는다.
#  3. 채점이 키워드 기반이라 정답의 60%를 거절로 오탐했다 → judge_answers.py로 교체.
#
# 학습은 두 판을 GPU 한 장씩 잡아 동시에 돌린다. 1.2B LoRA가 약 40GB라 한 장에 들어가고,
# 순차로 하면 벽시계가 두 배가 된다(실측 6~12분/판, docs/RESEARCH-compute-log.md).
set -e
cd /data/tta/mydoc-kg
export PATH="/data/tta/mydoc-kg/env/bin:$PATH"
BASE=/data/tta/EXAONE/exaone-4.0-1.2B-local
TEACHER=/data/tta/EXAONE/exaone-4.0.1-32B-local

# C-0은 등급 라벨 버그(weight 역산으로 27% 오라벨)를 고친 뒤 코퍼스를 다시 만드는
# 단계다. 교사 재생성이 필요하지만 실측 91초라 비싸지 않다.
#
#   미해결 — corpus/chat_sft_*/{train,dev}.jsonl 을 만드는 코드가 저장소에 없다.
#   교사 출력(runs/chat_teacher_v2.jsonl)에서 학습 코퍼스로 가는 분할이 수작업이었다.
#   그 스크립트를 복원하기 전까지 C-0은 손으로 돌려야 한다. 재현성 구멍이다.
#
# cd research
# python build_chat_sft_jobs.py --out chat_sft_jobs.jsonl
# python build_hard_abstention.py --out hard_abstention_jobs.jsonl --n 240   # 거절 대상 확보
# python gen_chat_teacher_v2.py --model $TEACHER --jobs chat_sft_jobs.jsonl \
#     --hard hard_abstention_jobs.jsonl --out ../runs/chat_teacher_v2.jsonl --tp 2
# (train/dev 분할 — 복원 필요)
# python build_chat_eval_split.py --jobs chat_sft_jobs.jsonl \
#     --hard hard_abstention_jobs.jsonl --train ../corpus/chat_sft_v2/train.jsonl \
#     --select chat_select_v2.jsonl --test chat_test.jsonl
# cd ..

echo "[C-1] v1/v2 병렬 학습 (GPU0/GPU1)"
CUDA_VISIBLE_DEVICES=0 python scripts/train_chat_sft.py --model $BASE \
  --data-dir corpus/chat_sft_v1 --out runs/chat_sft_v1 > runs/train_v1.log 2>&1 &
PID1=$!
CUDA_VISIBLE_DEVICES=1 python scripts/train_chat_sft.py --model $BASE \
  --data-dir corpus/chat_sft_v2 --out runs/chat_sft_v2 > runs/train_v2.log 2>&1 &
PID2=$!
wait $PID1 || { echo "v1 학습 실패"; tail -30 runs/train_v1.log; exit 1; }
wait $PID2 || { echo "v2 학습 실패"; tail -30 runs/train_v2.log; exit 1; }
tail -5 runs/train_v1.log runs/train_v2.log

# 생성과 채점을 분리한다. 체크포인트마다 베이스를 다시 올리지 않고 LoRA만 갈아끼운다.
echo "[C-2] 체크포인트별 답변 생성"
for V in v1 v2; do
  CUDA_VISIBLE_DEVICES=0 python scripts/select_chat_checkpoint_vllm.py --base $BASE \
    --ckpt-root runs/chat_sft_$V --eval research/chat_select_$V.jsonl \
    --out runs/chat_answers_$V.json
done

# 32B 채점기는 한 번만 올려 두 판을 같이 매긴다.
echo "[C-3] 32B 채점"
cd research
python judge_answers.py --model $TEACHER --answers ../runs/chat_answers_v1.json \
  --out ../runs/chat_judged_v1.json --tp 2
python judge_answers.py --model $TEACHER --answers ../runs/chat_answers_v2.json \
  --out ../runs/chat_judged_v2.json --tp 2
cd ..

echo "[C-4] 최고 체크포인트 병합"
for V in v1 v2; do
  BEST=$(python -c "import json;print(json.load(open('runs/chat_judged_$V.json'))['_best'])")
  STEP=${BEST#step}
  echo "  $V → checkpoint-$STEP"
  CUDA_VISIBLE_DEVICES=0 python scripts/merge_and_export.py --base $BASE \
    --adapter runs/chat_sft_$V/checkpoint-$STEP --out runs/chat_merged_$V
done

# 최종 수치는 선택에 쓰지 않은 테스트셋으로만 낸다. 같은 셋으로 고르고 발표하면
# 체크포인트 6개에 대한 선택 최적값이라 낙관 편향이 들어간다.
echo "[C-5] 테스트셋 최종 측정"
# 병합본을 --base로 넘긴다(어댑터 없이 그 모델 자체를 잰다). 순정 베이스도 같이 재서
# 대조군으로 쓴다.
for M in base=$BASE v1=runs/chat_merged_v1 v2=runs/chat_merged_v2; do
  NAME=${M%%=*}
  CUDA_VISIBLE_DEVICES=0 python scripts/select_chat_checkpoint_vllm.py \
    --base ${M#*=} --label $NAME --eval research/chat_test.jsonl \
    --out runs/chat_test_answers_$NAME.json
done
# 세 대상을 한 배치로 채점한다 — 32B를 한 번만 올린다.
cd research
python judge_answers.py --model $TEACHER --tp 2 \
  --answers ../runs/chat_test_answers_base.json \
           ../runs/chat_test_answers_v1.json \
           ../runs/chat_test_answers_v2.json \
  --out ../runs/chat_test_judged.json
echo "CHAT_SFT_DONE"
