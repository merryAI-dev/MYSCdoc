#!/usr/bin/env python3
"""
채점된 교사 답변 → 학습 코퍼스. 키워드 필터를 32B 판정으로 대체한다 (plan-v3 H2).

거르는 규칙 (전부 판정 결과 기준, 문자열 검사 없음):
  · 거절 대상(unanswerable*): response가 "거절"이 아니거나 fabricated → 제외.
    부분답변도 제외한다 — 답이 아예 없는 질문에 부분답변이 있을 수 없다. 있다면 지어냄이다.
  · 답 대상(answerable): fabricated → 제외. response가 "거절" → 제외(골드가 사실에 있는데
    거절한 것은 잘못된 시범). "답변"과 "부분답변"은 채택 — 부분답변이 바로 H1의 학습 목표다.

키워드 필터와의 차이를 실측으로 남긴다: 예전 방식이라면 통과했을 행 중 판정이 걸러낸
건수를 출력한다. 이것이 H2의 측정값이다.

사용:
  python build_corpus_from_judged.py --teacher ../runs/chat_teacher_v3.jsonl \
      --judged ../runs/chat_teacher_v3_judged.json --out ../runs/chat_corpus_v3.jsonl
"""
import argparse
import json
from collections import Counter

# H2 측정용으로만 쓴다 — 거르기에는 쓰지 않는다.
OLD_REFUSAL_MARKERS = ("없어요", "없습니다", "찾을 수 없", "확인할 수 없",
                       "담겨 있지 않", "나와 있지 않", "포함되어 있지 않")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", required=True)
    ap.add_argument("--judged", required=True, help="judge_answers.py 출력")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    teacher = [json.loads(l) for l in open(args.teacher)]
    judged_rows = json.load(open(args.judged))["teacher"]["rows"]
    verdict = {(r["question"], r["label"]): r for r in judged_rows}

    kept, stats = [], Counter()
    old_pass_new_drop = 0
    for t in teacher:
        v = verdict.get((t["question"], t["label"]))
        if v is None:
            stats["채점누락"] += 1
            continue
        neg = t["label"].startswith("unanswerable")
        if neg:
            ok = v["response"] == "거절" and not v["fabricated"]
        else:
            ok = v["response"] in ("답변", "부분답변") and not v["fabricated"]
        # H2 측정: 키워드 방식이라면 통과였는데 판정이 걸러낸 것
        old_ok = (any(m in t["answer"] for m in OLD_REFUSAL_MARKERS) if neg else True)
        if old_ok and not ok:
            old_pass_new_drop += 1
        stats[f"{'거절대상' if neg else '답대상'}:{'채택' if ok else '제외'}"] += 1
        if ok:
            kept.append(t)

    with open(args.out, "w") as f:
        for t in kept:
            f.write(json.dumps({
                "label": t["label"], "question": t["question"],
                "prompt": [{"role": "system", "content": t["system"]},
                           {"role": "user", "content": t["user"]}],
                "completion": [{"role": "assistant", "content": t["answer"]}],
            }, ensure_ascii=False) + "\n")

    print(f"[corpus] 채택 {len(kept)}/{len(teacher)} · {dict(stats)}")
    print(f"[H2] 키워드 필터라면 통과였을 오염 {old_pass_new_drop}건을 판정이 걸러냄")


if __name__ == "__main__":
    main()
