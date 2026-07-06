CREATE TABLE meet_ingest_log (
    id                uuid PRIMARY KEY,
    conference_record varchar(120) NOT NULL UNIQUE,
    document_id       uuid NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    created_at        timestamptz NOT NULL
);
