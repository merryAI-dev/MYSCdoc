CREATE TABLE tiro_ingest_log (
    id          uuid PRIMARY KEY,
    note_guid   varchar(64) NOT NULL UNIQUE,
    document_id uuid NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL
);
