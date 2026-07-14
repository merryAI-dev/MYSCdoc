-- Meetily(오픈소스 로컬 회의 비서) 임포트 dedup 장부 — Tiro(V7)와 동일 패턴.
CREATE TABLE meetily_ingest_log (
    id          uuid PRIMARY KEY,
    meeting_id  varchar(64) NOT NULL UNIQUE,
    document_id uuid NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL
);
