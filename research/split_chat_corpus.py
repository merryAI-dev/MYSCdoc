#!/usr/bin/env python3
"""
교사 출력 → 학습 코퍼스 train/dev 분할.

이 단계가 저장소에 없어서 재현이 끊겨 있었다(2026-07-28 감사에서 발견). 교사 출력
runs/chat_teacher_v*.jsonl 에서 corpus/chat_sft_v*/{train,dev}.jsonl 로 가는 분할이
수작업이었다. 여기 복원한다.

분할 규칙:
  · 질문 단위로 통째 배정한다. 정답 사례와 그 짝인 어려운 거절은 질문 문자열이 같아서
    행 단위로 쪼개면 같은 질문이 train과 dev에 나뉘어 들어간다 — 그게 누수다.
  · 라벨 조합별로 비율을 맞춘다. dev에 거절이 몰리거나 빠지면 학습 중 평가가 왜곡된다.
  · dev는 학습 중 관찰용일 뿐이다. 체크포인트 선택과 최종 보고는 build_chat_eval_split.py가
    만든 선택셋/테스트셋으로 한다. 셋을 겹쳐 쓰다가 발표 수치가 선택 최적값이 됐던 적이 있다.

사용:
  python split_chat_corpus.py --teacher ../runs/chat_teacher_v2.jsonl \
      --out-dir corpus/chat_sft_v2 --dev-frac 0.15
"""
import argparse
import json
import os
import random
from collections import Counter, defaultdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--dev-frac", type=float, default=0.15)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    rows = [json.loads(l) for l in open(args.teacher)]
    groups = defaultdict(list)
    for r in rows:
        groups[r["question"]].append(r)
    print(f"[입력] {len(rows)}행 · 질문 {len(groups)}개 · "
          f"{dict(Counter(r['label'] for r in rows))}")

    by_kind = defaultdict(list)
    for q, g in groups.items():
        by_kind[tuple(sorted(r["label"] for r in g))].append(g)

    rng = random.Random(args.seed)
    train, dev = [], []
    for kind, gs in by_kind.items():
        rng.shuffle(gs)
        n_dev = round(len(gs) * args.dev_frac)
        dev += [r for g in gs[:n_dev] for r in g]
        train += [r for g in gs[n_dev:] for r in g]
    rng.shuffle(train)
    rng.shuffle(dev)

    os.makedirs(args.out_dir, exist_ok=True)
    for name, rows_ in (("train", train), ("dev", dev)):
        path = os.path.join(args.out_dir, f"{name}.jsonl")
        with open(path, "w") as f:
            for r in rows_:
                # 학습에 필요한 두 필드만 남긴다. label/question은 분할 검증용이었다.
                f.write(json.dumps({"prompt": r["prompt"], "completion": r["completion"]},
                                   ensure_ascii=False) + "\n")
        print(f"[{name}] {len(rows_)}행 · {dict(Counter(r['label'] for r in rows_))} → {path}")

    overlap = {r["question"] for r in train} & {r["question"] for r in dev}
    print(f"[검증] train∩dev 질문 {len(overlap)}개 (0이어야 한다)")


if __name__ == "__main__":
    main()
