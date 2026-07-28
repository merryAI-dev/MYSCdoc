#!/usr/bin/env python3
"""
챗 평가셋을 선택용/테스트용으로 쪼갠다 — 학습 질문은 전부 뺀다.

왜 필요한가(독립 감사에서 적발, 2026-07-28). 지금까지 chat_eval*.jsonl과
corpus/chat_sft_*/dev.jsonl이 **같은 문항**이었다. 문자열과 시스템 프롬프트 해시가
일치한다. 그래서 같은 66건이

  (1) 학습 중 eval_strategy="epoch" 평가
  (2) Honesty로 체크포인트 고르기
  (3) 논문에 싣는 최종 수치

세 군데에 다 쓰였다. 최종 수치가 체크포인트 6개에 대한 선택 최적값이 되어 낙관 쪽으로
편향된다. 학습/평가 누수는 없었다(질문 기준 교집합 0) — 문제는 선택과 보고의 분리다.

  → 선택셋으로 고르고, 테스트셋으로만 발표한다.

거절 대상 수도 문제였다. chat_eval_v2.jsonl은 거절 대상이 9건뿐이라 Prudence가 한 건에
11%p씩 움직였고, 어려운 거절은 0건이라 v2의 핵심 능력을 아예 측정하지 못했다. 여기서는
쉬운 거절(그래프에 없는 주제)과 어려운 거절(주제는 겹치는데 답이 없음)을 양쪽에 고르게
나눠 담고, 각 셋의 거절 대상 수를 출력한다. 50건에 못 미치면 경고한다.

사용:
  python build_chat_eval_split.py --jobs chat_sft_jobs.jsonl \
      --hard hard_abstention_jobs.jsonl --train corpus/chat_sft_v2/train.jsonl \
      --select chat_select_v2.jsonl --test chat_test.jsonl
"""
import argparse
import json
import random
from collections import Counter, defaultdict


def question_of(row):
    """학습 코퍼스 행에서 질문을 뽑는다. 사용자 메시지 첫 줄이 '질문: ...' 형식이다."""
    user = [m for m in row["prompt"] if m["role"] == "user"][-1]["content"]
    first = user.strip().split("\n", 1)[0]
    return first[len("질문:"):].strip() if first.startswith("질문:") else first.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", required=True, help="chat_sft_jobs.jsonl (쉬운 거절 포함)")
    ap.add_argument("--hard", required=True, help="hard_abstention_jobs.jsonl")
    ap.add_argument("--train", required=True, help="학습에 쓴 corpus/*/train.jsonl")
    ap.add_argument("--select", required=True)
    ap.add_argument("--test", required=True)
    ap.add_argument("--seed", type=int, default=2026)
    args = ap.parse_args()

    trained = {question_of(json.loads(l)) for l in open(args.train)}
    print(f"[제외] 학습에 쓰인 질문 {len(trained)}개")

    system = None
    cases = []
    for line in open(args.jobs):
        j = json.loads(line)
        system = system or j["system"]
        cases.append({"label": j["label"], "question": j["question"],
                      "prompt": [{"role": "system", "content": j["system"]},
                                 {"role": "user", "content": j["user"]}]})
    for line in open(args.hard):
        j = json.loads(line)
        cases.append({"label": j["label"], "question": j["question"],
                      "prompt": [{"role": "system", "content": system},
                                 {"role": "user", "content":
                                     f"질문: {j['question']}\n\n지식그래프 사실:\n{j['facts']}\n"
                                     f"위 사실만 근거로, 각 사실의 확정도를 구분해서 답하세요.\n"}]})

    # 어려운 거절은 답 있는 질문과 문자열이 같다(같은 질문, 사실만 다름). 질문으로 거르면
    # 짝이 통째로 날아가므로 (질문, 라벨)로 중복을 판단한다.
    seen, pool = set(), []
    for c in cases:
        key = (c["question"], c["label"])
        if c["question"] in trained or key in seen:
            continue
        seen.add(key)
        pool.append(c)
    print(f"[후보] {len(pool)}건 · {dict(Counter(c['label'] for c in pool))}")

    # 질문 단위로 통째로 배정한다. 정답 사례와 그 짝인 어려운 거절은 질문 문자열이 같아서
    # 라벨별로 쪼개면 같은 질문이 선택셋과 테스트셋에 나뉘어 들어간다 — 그게 누수다.
    groups = defaultdict(list)
    for c in pool:
        groups[c["question"]].append(c)
    # 그룹의 라벨 조합별로 반씩 나눈다. 한쪽에 거절이 몰리면 두 셋이 비교 불가능해진다.
    by_kind = defaultdict(list)
    for q, rows in groups.items():
        by_kind[tuple(sorted(r["label"] for r in rows))].append(rows)
    rng = random.Random(args.seed)
    select, test = [], []
    for kind, gs in by_kind.items():
        rng.shuffle(gs)
        half = len(gs) // 2
        select += [c for g in gs[:half] for c in g]
        test += [c for g in gs[half:] for c in g]
    rng.shuffle(select)
    rng.shuffle(test)

    for name, path, rows in (("선택", args.select, select), ("테스트", args.test, test)):
        with open(path, "w") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        neg = sum(1 for r in rows if r["label"].startswith("unanswerable"))
        print(f"[{name}] {len(rows)}건 · 거절대상 {neg} · "
              f"{dict(Counter(r['label'] for r in rows))} → {path}")
        if neg < 50:
            print(f"  [주의] 거절 대상 {neg}건 — 1건이 {100/max(neg,1):.1f}%p씩 움직인다. "
                  f"build_hard_abstention.py --n 을 늘려 확보할 것")

    overlap = {c["question"] for c in select} & {c["question"] for c in test}
    print(f"[검증] 선택∩테스트 질문 {len(overlap)}개 · 학습∩평가 "
          f"{len(trained & {c['question'] for c in pool})}개 (둘 다 0이어야 한다)")


if __name__ == "__main__":
    main()
