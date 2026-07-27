#!/usr/bin/env python3
"""
M19-0: 학습 코퍼스 export — (입력 문서 텍스트 → 정규화된 온톨로지 JSON) 쌍을 JSONL로.

목적: 로컬 추출 모델(EXAONE-3.5) SFT용 distillation 데이터. 교사(Gemini)가 이미 추출해
knowledge_triple에 저장한 결과를 그대로 타깃으로 쓴다 — 출력이 M16~18 정규화를 거친
canonical이라 학생이 처음부터 깨끗한 구조를 배운다. Gemini 재호출 0 (zero-cost).

입력 텍스트는 문서 블록(ProseMirror JSON)에서 재구성하되, Tiro/Meetily가 심은 구조·메타
블록은 걸러 교사가 실제로 본 본문에 맞춘다(GoogleDriveIngestService.reconstructText와 동일 규칙).

사용: python export_training_corpus.py > corpus.jsonl
"""
import json
import re
import sys
import psycopg2

DSN = dict(host="localhost", dbname="mydoc", user="mydoc", password="changeme")

# 추출 입력에서 걸러낼 구조/메타 (재구성 규칙 — Java reconstructText와 일치)
TIRO_META = re.compile(r"^(작성자|참석자|녹음 시작|길이|출처)\s*:")
LEADING_TIMECODE = re.compile(r"^\[\d[^\]]{0,39}\]\s*")
META_HEADING = "회의 정보"


def block_text(content):
    """ProseMirror content jsonb → 평문."""
    parts = []
    for node in (content or {}).get("content", []) or []:
        parts.append(node.get("text", ""))
    return "".join(parts)


def reconstruct_input(blocks):
    """블록 목록 → 교사가 본 입력 텍스트 (헤딩/메타/타임코드 제거)."""
    out, in_meta = [], False
    for btype, content in blocks:
        line = block_text(content)
        if btype != "PARAGRAPH":
            in_meta = (line.strip() == META_HEADING)
            continue
        line = LEADING_TIMECODE.sub("", line)
        if not line.strip():
            continue
        if in_meta and TIRO_META.match(line):
            continue
        out.append(line)
    return "\n".join(out)


def main():
    conn = psycopg2.connect(**DSN)
    cur = conn.cursor()
    # 트리플 있는 문서만 (학습쌍 성립)
    cur.execute("""
        SELECT d.id, d.title, d.event_at,
               (SELECT channel_id FROM knowledge_triple t WHERE t.document_id=d.id LIMIT 1)
        FROM document d
        WHERE EXISTS (SELECT 1 FROM knowledge_triple t WHERE t.document_id=d.id)
        ORDER BY d.created_at
    """)
    docs = cur.fetchall()
    emitted = skipped = 0
    for doc_id, title, event_at, source in docs:
        cur.execute("SELECT type, content FROM block WHERE document_id=%s ORDER BY position", (doc_id,))
        blocks = cur.fetchall()
        input_text = reconstruct_input(blocks)
        if len(input_text) < 40:  # 본문이 너무 짧으면 학습 가치 없음
            skipped += 1
            continue
        cur.execute("""
            SELECT kind, statement, subject, predicate, object
            FROM knowledge_triple WHERE document_id=%s ORDER BY id
        """, (doc_id,))
        triples = [
            {"kind": k, "statement": s, "subject": subj, "predicate": p, "object": o}
            for (k, s, subj, p, o) in cur.fetchall()
        ]
        if not triples:
            skipped += 1
            continue
        # 학습 샘플: instruction은 SFT 스크립트에서 템플릿으로 감싼다. 여기선 input/output만.
        sample = {
            "source": source,
            "title": title,
            "input": input_text[:8000],  # 컨텍스트 상한 (학습 안정)
            "output": {"triples": triples},
        }
        sys.stdout.write(json.dumps(sample, ensure_ascii=False) + "\n")
        emitted += 1
    conn.close()
    sys.stderr.write(f"[corpus] 문서 {len(docs)} 중 emit {emitted} · skip {skipped}\n")


if __name__ == "__main__":
    main()
