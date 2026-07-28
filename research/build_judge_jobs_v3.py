#!/usr/bin/env python3
"""
등급 판정 잡 v3 — DB에서 직접, topic-키로 만든다.

v1 판정의 topic 매핑은 저장소 밖에서 위치 기반으로 역구성됐다("판정 항목 순서 =
decisionPoints 순서"). 재현 구멍이고, 등급 역산 사고(27% 오라벨)와 같은 계열 —
원본 키를 버리고 나중에 복원하려던 것이 문제였다. 여기서는 결정 노드의 topic을
잡 항목에 **함께 실어** 판정 결과가 트리플과 바로 조인되게 한다.

항목 text는 topic이 아니라 결정의 statement 전문이다. topic("다운로드 기능")만으로는
판정 근거가 부족하다. 문맥은 block 테이블의 문서 원문.

사용: MYDOC_DB_PASSWORD=... venv/bin/python build_judge_jobs_v3.py --out judge_jobs_v3.jsonl
"""
import argparse
import json
import os

import psycopg2

MAX_ITEMS = 40          # judge_weights_v2.py의 ITEM_IDS 상한과 맞춘다
MAX_CONTEXT = 20000     # 32B max_model_len 32768 안에서 판정 출력 여유 확보


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    conn = psycopg2.connect(host="localhost", dbname="mydoc", user="mydoc",
                            password=os.environ["MYDOC_DB_PASSWORD"])
    cur = conn.cursor()
    # 결정 노드마다 statement 하나 (엣지들이 같은 statement를 공유하므로 min으로 대표)
    cur.execute("""SELECT document_id, subject, min(statement)
                   FROM knowledge_triple
                   WHERE subject LIKE '결정:%' AND statement IS NOT NULL
                   GROUP BY document_id, subject
                   ORDER BY document_id, subject""")
    nodes = cur.fetchall()

    # 문서 원문: block(ProseMirror jsonb)을 position 순으로 읽어 텍스트만 뽑는다
    def block_text(node):
        if isinstance(node, dict):
            own = node.get("text", "")
            return own + "".join(block_text(ch) for ch in node.get("content", []))
        return ""

    cur.execute("SELECT document_id, content FROM block ORDER BY document_id, position")
    contexts = {}
    for bid, content in cur.fetchall():
        t = block_text(content).strip()
        if t:
            contexts.setdefault(str(bid), []).append(t)
    contexts = {k: "\n".join(v) for k, v in contexts.items()}
    conn.close()

    by_doc = {}
    for did, subject, statement in nodes:
        by_doc.setdefault(str(did), []).append(
            {"topic": subject[len("결정:"):].strip(), "text": statement})

    dropped_ctx = dropped_over = 0
    with open(args.out, "w") as f:
        for did, items in by_doc.items():
            ctx = contexts.get(did) if did in contexts else None
            if ctx is None:
                # UUID 키 타입 불일치 대비
                ctx = next((v for k, v in contexts.items() if str(k) == did), None)
            if not ctx:
                dropped_ctx += len(items)
                continue
            if len(items) > MAX_ITEMS:
                dropped_over += len(items) - MAX_ITEMS
                items = items[:MAX_ITEMS]
            f.write(json.dumps({
                "doc_id": did, "context": ctx[:MAX_CONTEXT],
                "items": [{"id": f"D{i:03d}", "topic": it["topic"], "text": it["text"]}
                          for i, it in enumerate(items, 1)],
            }, ensure_ascii=False) + "\n")

    total = sum(len(v) for v in by_doc.values())
    print(f"[jobs] 문서 {len(by_doc)} · 노드 {total} · 원문 없음 제외 {dropped_ctx} "
          f"· 상한 초과 제외 {dropped_over} → {args.out}")
    if dropped_ctx:
        print("  [주의] 원문 없는 노드가 있다 — 해당 노드는 미분류로 남는다")


if __name__ == "__main__":
    main()
