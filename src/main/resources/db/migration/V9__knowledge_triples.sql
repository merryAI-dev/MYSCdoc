CREATE TABLE knowledge_triple (
    id          uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kind        varchar(30)  NOT NULL,
    statement   varchar(1000) NOT NULL,
    subject     varchar(200) NOT NULL,
    predicate   varchar(200) NOT NULL,
    object      varchar(500) NOT NULL,
    channel_id  varchar(50)  NOT NULL,
    thread_ts   varchar(50)  NOT NULL,
    created_at  timestamptz  NOT NULL
);

CREATE INDEX knowledge_triple_document_idx ON knowledge_triple (document_id);
CREATE INDEX knowledge_triple_subject_idx ON knowledge_triple (subject);
