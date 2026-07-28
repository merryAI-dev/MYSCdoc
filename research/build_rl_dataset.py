#!/usr/bin/env python3
"""
Phase 0 — Search RL 데이터셋 구축 + SearchGym식 코퍼스 감사 (가설 H3·H4·H5·H18).

문헌의 실패 원인을 사전에 차단한다:
  H3 검색 불가능한 골드 → 보상 오염 → 붕괴 (SearchGym: 결함 20.6%가 붕괴 원인)
     → 골드 노드가 naive 쿼리로 top-50 안에 안 들면 학습에서 제외한다.
  H4 질문에 답이 문자로 들어가면 에코만으로 hit → 오염 검사로 제외.
  H5 naive로 이미 top-1이면 개선 여지 없음 → 제외하진 않되 비중을 기록한다.
  H18 코퍼스 암기 방지 → 분할은 문서 단위 (같은 회의의 질문이 양쪽에 못 감).

출력: rl/rl_corpus.jsonl(검색 코퍼스), rl/train.jsonl, rl/val.jsonl,
      baseline(질문 그대로 검색) hit@k — RL이 이겨야 할 1차 기준선.
"""
import json
import os
import random

import psycopg2
from rank_bm25 import BM25Okapi

import korean_syntax as ks

CONTENT = ("NNG", "NNP", "NNB", "NR", "SL", "SN", "VV", "VA", "XR")
TEMPLATES = [
    "{t} 어떻게 하기로 했나요?",
    "{t} 관련해서 정해진 내용이 뭐예요?",
    "{t}에 대한 결정 사항을 찾아줘",
]


def toks(text):
    return [m.form for m in ks.kiwi().tokenize(text or "") if m.tag in CONTENT]


def main():
    conn = psycopg2.connect(host="localhost", dbname="mydoc", user="mydoc",
                            password="changeme")
    cur = conn.cursor()
    cur.execute("SELECT document_id, subject, predicate, object, statement FROM knowledge_triple")
    rows = cur.fetchall()
    conn.close()

    os.makedirs("rl", exist_ok=True)
    corpus, node_idx = [], {}
    for did, sub, pred, obj, st in rows:
        i = len(corpus)
        corpus.append({"i": i, "doc_id": str(did), "subject": sub,
                       "text": f"{sub} {pred} {obj} {st}"})
        node_idx.setdefault((str(did), sub), []).append(i)
    with open("rl/rl_corpus.jsonl", "w") as f:
        for c in corpus:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    bm25 = BM25Okapi([toks(c["text"]) for c in corpus])

    def best_rank(query_tokens, gold):
        if not query_tokens:
            return None
        scores = bm25.get_scores(query_tokens)
        order = sorted(range(len(corpus)), key=lambda i: -scores[i])
        for r, i in enumerate(order[:50], 1):
            if scores[i] <= 0:
                break
            if i in gold:
                return r
        return None

    # 결정 노드 → 질문 생성 + 감사
    decisions = {}
    for did, sub, pred, obj, _ in rows:
        if sub.startswith("결정:") and pred == "값":
            decisions[(str(did), sub)] = obj

    kept, dropped_unfindable, dropped_contaminated = [], 0, 0
    trivial = 0
    rng = random.Random(42)
    for (did, sub), outcome in decisions.items():
        topic = sub[len("결정:"):]
        gold = set(node_idx[(did, sub)])
        out_toks = set(toks(outcome))
        q_text = rng.choice(TEMPLATES).format(t=topic)
        q_toks = toks(q_text)
        # H4: 질문 토큰에 답(outcome) 토큰이 섞이면 오염 — 제외
        if out_toks and len(out_toks & set(q_toks)) / len(out_toks) > 0.5:
            dropped_contaminated += 1
            continue
        # H3: naive(질문 그대로)로 top-50에 골드가 없으면 보상 오염원 — 제외
        rank = best_rank(q_toks, gold)
        if rank is None:
            dropped_unfindable += 1
            continue
        if rank == 1:
            trivial += 1
        kept.append({"question": q_text, "doc_id": did, "subject": sub,
                     "gold": sorted(gold), "naive_rank": rank})

    # H18: 문서 단위 분할
    doc_ids = sorted({k["doc_id"] for k in kept})
    rng.shuffle(doc_ids)
    val_docs = set(doc_ids[:max(1, len(doc_ids) * 15 // 100)])
    train = [k for k in kept if k["doc_id"] not in val_docs]
    val = [k for k in kept if k["doc_id"] in val_docs]
    for name, items in (("train", train), ("val", val)):
        with open(f"rl/{name}.jsonl", "w") as f:
            for k in items:
                f.write(json.dumps(k, ensure_ascii=False) + "\n")

    # baseline: 질문 그대로 검색 (RL이 이겨야 할 기준선 H19-a)
    def hits(items):
        h = {1: 0, 5: 0, 10: 0}
        for k in items:
            r = k["naive_rank"]
            for kk in h:
                if r and r <= kk:
                    h[kk] += 1
        n = len(items)
        return {kk: h[kk] / n for kk in h}

    hb = hits(val)
    print(f"[감사] 결정 노드 {len(decisions)} → 채택 {len(kept)} · "
          f"검색불가 제외 {dropped_unfindable} · 오염 제외 {dropped_contaminated}")
    print(f"[headroom] naive top-1 자명 질문 {trivial}/{len(kept)} ({trivial/len(kept):.0%})"
          f" — 나머지 {1-trivial/len(kept):.0%}가 학습 여지")
    print(f"[분할] train {len(train)} · val {len(val)} (문서 단위, val {len(val_docs)}문서)")
    print(f"[baseline·질문그대로] val hit@1 {hb[1]:.1%} · @5 {hb[5]:.1%} · @10 {hb[10]:.1%}")


if __name__ == "__main__":
    main()
