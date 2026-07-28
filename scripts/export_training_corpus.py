#!/usr/bin/env python3
"""
M19-0: 학습·평가 코퍼스 export — (원문 → 구조화 추출 JSON) 쌍을 JSONL로.

용도 두 가지:
  1) 평가 입력 — 같은 원문을 EXAONE/Gemini에 넣어 조건별 산출물을 비교 (논문 조건 A/B/C/D)
  2) SFT 타깃 — Gemini(교사) 산출물을 EXAONE(학생)에 증류

타깃 JSON은 knowledge_triple의 reified 행에서 역구성한다. M20에서 결정을 사건 노드로
분해해 저장하므로 (결정:대상)의 주체·값·근거·조건·기각대안을 모으면 원래의 추출 구조가
그대로 복원된다. 출력은 M16~18 정규화를 거친 canonical이라 학생이 처음부터 깨끗한
스키마를 배운다. Gemini 재호출 0.

입력 텍스트는 문서 블록에서 재구성하되 구조·메타 블록은 제외한다
(GoogleDriveIngestService.reconstructText와 동일 규칙 — 교사가 실제로 본 본문에 맞춤).

사용: python export_training_corpus.py > corpus.jsonl
"""
import json
import os
import re
import sys
from collections import defaultdict

import psycopg2

DSN = dict(host="localhost", dbname="mydoc", user="mydoc", password=os.environ["MYDOC_DB_PASSWORD"])

TIRO_META = re.compile(r"^(작성자|참석자|녹음 시작|길이|출처)\s*:")
LEADING_TIMECODE = re.compile(r"^\[\d[^\]]{0,39}\]\s*")
META_HEADING = "회의 정보"
DECISION_PREFIX = "결정:"


def block_text(content):
    return "".join(node.get("text", "") for node in (content or {}).get("content", []) or [])


def reconstruct_input(blocks):
    """블록 → 교사가 본 입력 텍스트 (헤딩·메타·타임코드 제거)."""
    out, in_meta = [], False
    for btype, content in blocks:
        line = block_text(content)
        if btype != "PARAGRAPH":
            in_meta = line.strip() == META_HEADING
            continue
        line = LEADING_TIMECODE.sub("", line)
        if not line.strip() or (in_meta and TIRO_META.match(line)):
            continue
        out.append(line)
    return "\n".join(out)


def build_target(rows):
    """reified 트리플 → 추출 JSON(decisionPoints + tacitKnowledge) 역구성."""
    decisions = defaultdict(lambda: {"owner": [], "outcome": [], "alternatives": [],
                                     "topic": "", "rationale": "", "condition": "", "decision": ""})
    tacit = defaultdict(lambda: {"kind": "", "statement": "", "triples": []})

    for kind, statement, subject, predicate, obj in rows:
        if subject.startswith(DECISION_PREFIX):
            d = decisions[subject]
            d["decision"] = d["decision"] or statement.split(" — ")[0]
            if predicate == "주체":
                d["owner"].append(obj)
            elif predicate == "대상":
                d["topic"] = obj
            elif predicate == "값":
                d["outcome"].append(obj)
            elif predicate == "근거":
                d["rationale"] = obj
            elif predicate == "조건":
                d["condition"] = obj
            elif predicate == "기각대안":
                d["alternatives"].append(obj)
        else:
            t = tacit[statement]
            t["kind"] = kind
            t["statement"] = statement
            t["triples"].append({"subject": subject, "predicate": predicate, "object": obj})

    decision_points = []
    for d in decisions.values():
        decision_points.append({
            "decision": d["decision"],
            "topic": d["topic"],
            "outcome": ", ".join(d["outcome"]),
            "owner": ", ".join(d["owner"]),
            "rationale": d["rationale"],
            "condition": d["condition"],
            "alternatives": d["alternatives"],
        })
    return {"decisionPoints": decision_points, "tacitKnowledge": list(tacit.values())}


def main():
    conn = psycopg2.connect(**DSN)
    cur = conn.cursor()
    cur.execute("""
        SELECT d.id, d.title, d.event_at,
               (SELECT channel_id FROM knowledge_triple t WHERE t.document_id = d.id LIMIT 1)
        FROM document d
        WHERE EXISTS (SELECT 1 FROM knowledge_triple t WHERE t.document_id = d.id)
        ORDER BY d.created_at
    """)
    docs = cur.fetchall()
    emitted = skipped = 0
    for doc_id, title, event_at, source in docs:
        cur.execute("SELECT type, content FROM block WHERE document_id=%s ORDER BY position", (doc_id,))
        input_text = reconstruct_input(cur.fetchall())
        if len(input_text) < 40:
            skipped += 1
            continue
        cur.execute("""
            SELECT kind, statement, subject, predicate, object
            FROM knowledge_triple WHERE document_id=%s ORDER BY subject, predicate
        """, (doc_id,))
        target = build_target(cur.fetchall())
        if not target["decisionPoints"] and not target["tacitKnowledge"]:
            skipped += 1
            continue
        sys.stdout.write(json.dumps({
            "doc_id": str(doc_id),
            "source": source,
            "title": title,
            "event_at": event_at.isoformat() if event_at else None,
            "input": input_text[:24000],   # 32B는 131k 컨텍스트지만 학습 안정 위해 절단
            "target": target,              # 교사(Gemini) 산출물 — SFT 타깃 겸 조건 A 기준선
        }, ensure_ascii=False) + "\n")
        emitted += 1
    conn.close()
    sys.stderr.write(f"[corpus] 문서 {len(docs)} 중 emit {emitted} · skip {skipped}\n")


if __name__ == "__main__":
    main()
