-- Node 편집 평면이 직접 읽고 쓰는 테이블. JPA 엔티티를 만들지 않는다.
CREATE TABLE yjs_update (
    id          bigserial PRIMARY KEY,
    doc_id      uuid  NOT NULL,
    update_data bytea NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_yjs_update_doc ON yjs_update(doc_id, id);

-- 컴팩션 결과 저장. doc_id당 최신 1행만 유지한다.
CREATE TABLE yjs_snapshot (
    doc_id      uuid PRIMARY KEY,
    state_data  bytea NOT NULL,
    last_update_id bigint NOT NULL,     -- 이 스냅샷에 포함된 마지막 yjs_update.id
    updated_at  timestamptz NOT NULL DEFAULT now()
);
