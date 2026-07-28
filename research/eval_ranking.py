#!/usr/bin/env python3
"""
검색 랭킹 평가 — 의미 가중치가 실제 검색을 좋게 하는가 (추출 아님, 검색).

known-item retrieval: DB의 reified 결정 노드마다 "topic은 어떻게 정했지?"류 질의를
자동 생성하고, 그 결정의 트리플이 상위 k에 오는지 잰다. 라벨 0건으로 지금 가능한
유일한 검색 평가이며, 완전한 적합성 평가가 아니라 '가지고 있는 것을 찾아내는가'의
바닥 점검이다. 가드레일 포함: 가중치 도입으로 원래 찾히던 것이 사라지면 실패.

비교 조건:
  A) BM25 단독 (현 제품과 동일한 방식)
  B) BM25 × 의미 가중치 (32B commitment·salience → 노드 가중치)
  C) B + 최신성 감쇠 (event_at, τ=180일)

가중치 근거: commitment 등급은 32B 판정(940건), 계수는 등급 순서만 보존하는 단순 설정.
계수 자체의 최적화는 라벨이 생긴 뒤의 일이다 — 여기서는 '순서 보존 가중치만으로 개선되는가'만 본다.
"""
import json
import math
import os
from datetime import datetime, timezone

import psycopg2
from rank_bm25 import BM25Okapi

import korean_syntax as ks

COMMIT_W = {"확정": 1.0, "조건부확정": 0.9, "의지예정": 0.7,
            "제안검토": 0.45, "당위": 0.4, "비결정": 0.2}
SAL_W = {"core": 1.0, "supporting": 0.75, "peripheral": 0.5}
TAU_DAYS = 180
NOW = datetime(2026, 7, 27, tzinfo=timezone.utc)

CONTENT = ("NNG", "NNP", "NNB", "NR", "SL", "SN", "VV", "VA", "XR")


def tokens(text):
    return [m.form for m in ks.kiwi().tokenize(text or "") if m.tag in CONTENT]


def main():
    conn = psycopg2.connect(host="localhost", dbname="mydoc", user="mydoc",
                            password=os.environ["MYDOC_DB_PASSWORD"])
    cur = conn.cursor()
    cur.execute("""SELECT id, document_id, subject, predicate, object, statement, event_at
                   FROM knowledge_triple""")
    triples = cur.fetchall()
    conn.close()
    print(f"트리플 {len(triples)}건 로드")

    # 코퍼스: Java corpusText와 같은 구성 (statement + s/p/o)
    docs_tokens = [tokens(f"{s} {sub} {pred} {obj}")
                   for _, _, sub, pred, obj, s, _ in triples]
    bm25 = BM25Okapi(docs_tokens)

    # 노드 가중치: (doc_id, "결정:topic") → weight
    weights = {}
    for m in json.load(open("runs/node_weights.json")):
        key = (m["doc_id"], "결정:" + m["topic"].strip())
        weights[key] = COMMIT_W[m["commitment"]] * SAL_W[m["salience"]]

    def node_weight(doc_id, subject):
        return weights.get((str(doc_id), subject.strip()), 0.7)   # 미판정 노드 중립값

    def recency(event_at):
        if event_at is None:
            return 0.8
        age = max((NOW - event_at).days, 0)
        return math.exp(-age / TAU_DAYS)

    # 평가 질의: reified 결정 노드의 (topic, 값) — 값 있는 노드만
    cur_targets = {}
    for i, (tid, did, sub, pred, obj, st, ev) in enumerate(triples):
        if sub.startswith("결정:") and pred == "값":
            cur_targets.setdefault((str(did), sub), []).append(i)
    # 같은 결정의 모든 트리플 인덱스 (정답 집합 = 해당 노드의 아무 엣지)
    node_rows = {}
    for i, (tid, did, sub, *_rest) in enumerate(triples):
        node_rows.setdefault((str(did), sub), []).append(i)

    queries = []
    for (did, sub), _ in cur_targets.items():
        topic = sub[len("결정:"):]
        q = tokens(topic)
        if len(q) >= 2:                      # 한 단어 질의는 변별력이 없어 제외
            queries.append(((did, sub), q))
    print(f"평가 질의 {len(queries)}건 (topic 2형태소 이상)")

    def evaluate(score_fn, k_list=(1, 5, 10)):
        hits = {k: 0 for k in k_list}
        ranks = []
        for (key, q) in queries:
            base = bm25.get_scores(q)
            scored = [(score_fn(base[i], triples[i]), i) for i in range(len(triples))
                      if base[i] > 0]
            scored.sort(reverse=True)
            gold = set(node_rows[key])
            rank = next((r for r, (_, i) in enumerate(scored, 1) if i in gold), None)
            ranks.append((key, rank))
            for k in k_list:
                if rank is not None and rank <= k:
                    hits[k] += 1
        n = len(queries)
        return {k: hits[k] / n for k in k_list}, ranks

    conds = {
        "A_bm25": lambda s, t: s,
        "B_+의미가중치": lambda s, t: s * (0.4 + 0.6 * node_weight(t[1], t[2])),
        "C_+최신성": lambda s, t: s * (0.4 + 0.6 * node_weight(t[1], t[2]))
                                   * (0.5 + 0.5 * recency(t[6])),
    }
    results, all_ranks = {}, {}
    for name, fn in conds.items():
        results[name], all_ranks[name] = evaluate(fn)

    print(f"\n{'조건':16s} hit@1   hit@5   hit@10")
    for name, r in results.items():
        print(f"{name:16s} {r[1]:.1%}  {r[5]:.1%}  {r[10]:.1%}")

    # 가드레일: A에서 top10이던 질의가 B/C에서 밀려났는가
    a_rank = {key: rk for key, rk in all_ranks["A_bm25"]}
    for name in ("B_+의미가중치", "C_+최신성"):
        lost = sum(1 for key, rk in all_ranks[name]
                   if a_rank.get(key) and a_rank[key] <= 10 and (rk is None or rk > 10))
        gained = sum(1 for key, rk in all_ranks[name]
                     if (a_rank.get(key) is None or a_rank[key] > 10) and rk and rk <= 10)
        print(f"[가드레일] {name}: top10 상실 {lost} · 신규진입 {gained}")


if __name__ == "__main__":
    main()
