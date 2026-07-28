#!/usr/bin/env python3
"""
V14 weight 컬럼 백필 — 32B 의미 판정을 실제 DB 노드에 붙인다.

매핑: 판정 항목 순서 = corpus target decisionPoints 순서(D001=첫번째)이고, corpus는
DB reified 행에서 역구성됐으므로 subject = "결정:"+topic으로 되돌아간다.
weight = commitment 계수 × salience 계수 (계수는 등급 순서만 보존하는 단순 설정 —
최적화는 라벨 축적 후의 일).

결정 노드의 모든 엣지(주체/대상/값/근거/조건/기각대안)에 같은 weight를 준다.
tacit 트리플은 이번 판정 범위 밖 — null(중립)로 남긴다.

사용: venv/bin/python backfill_weights.py
"""
import json

import psycopg2

COMMIT_W = {"확정": 1.0, "조건부확정": 0.9, "의지예정": 0.7,
            "제안검토": 0.45, "당위": 0.4, "비결정": 0.2}
SAL_W = {"core": 1.0, "supporting": 0.75, "peripheral": 0.5}

conn = psycopg2.connect(host="localhost", dbname="mydoc", user="mydoc", password="changeme")
cur = conn.cursor()

updated = missed = 0
for m in json.load(open("runs/node_weights.json")):
    w = COMMIT_W[m["commitment"]] * SAL_W[m["salience"]]
    cur.execute(
        "UPDATE knowledge_triple SET weight = %s WHERE document_id = %s AND subject = %s",
        (w, m["doc_id"], "결정:" + m["topic"].strip()))
    if cur.rowcount:
        updated += cur.rowcount
    else:
        missed += 1
conn.commit()

cur.execute("SELECT count(*) FILTER (WHERE weight IS NOT NULL), count(*) FROM knowledge_triple")
with_w, total = cur.fetchone()
print(f"[backfill] 판정 940건 적용 → 트리플 {updated}행 갱신 (매칭 실패 {missed}건)")
print(f"[backfill] weight 보유 {with_w}/{total} ({with_w/total:.0%}) — 나머지는 중립(null)")
conn.close()
