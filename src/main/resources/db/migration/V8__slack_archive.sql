CREATE TABLE slack_archive_message (
    id         uuid PRIMARY KEY,
    channel_id varchar(50) NOT NULL,
    ts         varchar(50) NOT NULL,
    thread_ts  varchar(50) NOT NULL,
    user_id    varchar(50) NOT NULL,
    text       text NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (channel_id, ts)
);

CREATE INDEX slack_archive_message_thread_idx ON slack_archive_message (channel_id, thread_ts);

CREATE TABLE slack_decision_log (
    id          uuid PRIMARY KEY,
    channel_id  varchar(50) NOT NULL,
    thread_ts   varchar(50) NOT NULL,
    last_ts     varchar(50) NOT NULL,
    document_id uuid REFERENCES document(id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    UNIQUE (channel_id, thread_ts)
);
