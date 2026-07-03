CREATE TABLE slack_ingest_log (
    id          uuid PRIMARY KEY,
    channel_id  varchar(50)  NOT NULL,
    thread_ts   varchar(50)  NOT NULL,
    document_id uuid NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    created_at  timestamptz  NOT NULL,
    UNIQUE (channel_id, thread_ts)
);
