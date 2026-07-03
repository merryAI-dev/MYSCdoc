CREATE TABLE chunk (
    id           uuid PRIMARY KEY,
    document_id  uuid NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    heading_path varchar(1000) NOT NULL,
    text         text NOT NULL,
    embedding    vector(1536),
    created_at   timestamptz NOT NULL
);
CREATE INDEX idx_chunk_document ON chunk(document_id);
CREATE INDEX idx_chunk_embedding ON chunk
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 키워드 검색용. 'simple'은 한국어 형태소 분석을 못 하므로 공백 단위 토큰화만 된다.
-- 검색 품질이 문제되면 OpenSearch + nori 도입을 검토한다 (스펙 밖 — 임의 구현 금지).
ALTER TABLE chunk ADD COLUMN ts tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', text)) STORED;
CREATE INDEX idx_chunk_ts ON chunk USING gin(ts);
