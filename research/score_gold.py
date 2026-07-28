#!/usr/bin/env python3
"""
골드 라벨 채점 — 사람 판단으로 (1) 시스템 간 실제 정확도, (2) 프록시의 타당성을 잰다.

두 질문에 답한다:
  Q1. 학생과 Gemini의 실제 정확도 차이가 있는가?
      → modal_grounded의 7pt 격차가 진짜 품질 차이인지, 프록시 아티팩트인지 갈린다.
  Q2. modal_grounded가 사람 판단과 상관이 있는가?
      → 상관이 없으면 그 지표로 쌓아올린 결론을 전부 재검토해야 한다.

Q2가 핵심이다. 프록시를 검증하지 않은 채 프록시로 모델을 고르면 Goodhart에 빠진다.

사용: venv/bin/python score_gold.py --labels ~/Downloads/gold_labels.json
"""
import argparse
import json
import math

import korean_syntax as ks
from measure_syntax_grounding import analyze_source, content_set


def z_test(k1, n1, k2, n2):
    p1, p2 = k1 / n1, k2 / n2
    se = math.sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2)
    if se == 0:
        return p1, p2, float("nan"), 1.0
    z = (p1 - p2) / se
    return p1, p2, z, math.erfc(abs(z) / math.sqrt(2))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", required=True, help="앱에서 내보낸 gold_labels.json")
    ap.add_argument("--key", default="gold_key.json")
    ap.add_argument("--corpus", default="corpus/eval80.jsonl")
    ap.add_argument("--theta", type=float, default=0.3)
    args = ap.parse_args()

    labels = json.load(open(args.labels))
    key = json.load(open(args.key))
    corpus = {json.loads(l)["doc_id"]: json.loads(l) for l in open(args.corpus)}

    judged = {k: v for k, v in labels.items() if k in key and v in ("correct", "wrong")}
    unclear = sum(1 for v in labels.values() if v == "unclear")
    print(f"[gold] 라벨 {len(labels)} · 채점대상 {len(judged)} (애매 {unclear}건 제외)\n")

    # Q1 — 시스템별 실제 정확도
    by_system = {}
    for item_id, verdict in judged.items():
        s = key[item_id]["system"]
        hit, tot = by_system.get(s, (0, 0))
        by_system[s] = (hit + (verdict == "correct"), tot + 1)

    print("=== Q1. 사람 판단 기준 실제 정확도 ===")
    for s, (hit, tot) in sorted(by_system.items()):
        print(f"  {s:28s} {hit}/{tot} = {hit/tot:.1%}")
    if len(by_system) == 2:
        (s1, (k1, n1)), (s2, (k2, n2)) = sorted(by_system.items())
        p1, p2, z, p = z_test(k1, n1, k2, n2)
        print(f"  차이 {(p1-p2)*100:+.1f}pt · z={z:.2f} · p={p:.3f} "
              f"{'★유의' if p < 0.05 else '유의하지 않음'}")

    # Q2 — 프록시 타당성: modal_grounded와 사람 판단이 같이 움직이는가
    sources = {}
    grounded_true = grounded_false = 0
    tp = fp = fn = tn = 0
    for item_id, verdict in judged.items():
        meta = key[item_id]
        doc_id = meta["doc_id"]
        if doc_id not in sources:
            sources[doc_id] = analyze_source(corpus[doc_id]["input"])
        claim = content_set(meta["claim"])
        if not claim:
            continue
        support = [is_modal for words, is_modal in sources[doc_id]
                   if len(claim & words) / len(claim) >= args.theta]
        proxy = bool(support) and any(support)      # modal_grounded 판정
        human = verdict == "correct"
        if proxy and human:
            tp += 1
        elif proxy and not human:
            fp += 1
        elif not proxy and human:
            fn += 1
        else:
            tn += 1
        if human:
            grounded_true += proxy
        else:
            grounded_false += proxy

    n_true = tp + fn
    n_false = fp + tn
    print(f"\n=== Q2. 프록시(modal_grounded) 타당성 · θ={args.theta} ===")
    print(f"  사람이 '맞음'이라 한 것 중 프록시 통과: {tp}/{n_true} = "
          f"{tp/n_true:.1%}" if n_true else "  (맞음 표본 없음)")
    print(f"  사람이 '아님'이라 한 것 중 프록시 통과: {fp}/{n_false} = "
          f"{fp/n_false:.1%}" if n_false else "  (아님 표본 없음)")
    if n_true and n_false:
        _, _, z, p = z_test(tp, n_true, fp, n_false)
        lift = tp / n_true - fp / n_false
        print(f"  변별력(lift): {lift*100:+.1f}pt · z={z:.2f} · p={p:.3f}")
        if p < 0.05 and lift > 0:
            print("  → 프록시가 사람 판단과 같은 방향으로 움직인다. 지표 사용 근거 있음.")
        else:
            print("  → 프록시가 정오를 변별하지 못한다. modal_grounded 기반 결론은 재검토 필요.")

    # 파이 계수 (이진 상관)
    denom = math.sqrt((tp + fp) * (tp + fn) * (tn + fp) * (tn + fn))
    if denom:
        phi = (tp * tn - fp * fn) / denom
        print(f"  φ 상관계수: {phi:+.3f}")


if __name__ == "__main__":
    main()
