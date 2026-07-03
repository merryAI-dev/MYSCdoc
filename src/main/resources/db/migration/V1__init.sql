CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE space (
    id          uuid PRIMARY KEY,
    slug        varchar(100) NOT NULL UNIQUE,
    name        varchar(200) NOT NULL,
    created_at  timestamptz  NOT NULL
);

CREATE TABLE member (
    id            uuid PRIMARY KEY,
    email         varchar(320) NOT NULL UNIQUE,
    display_name  varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL,
    slack_user_id varchar(50),
    created_at    timestamptz  NOT NULL
);

CREATE TABLE document (
    id          uuid PRIMARY KEY,
    space_id    uuid NOT NULL REFERENCES space(id),
    title       varchar(500) NOT NULL,
    owner_id    uuid NOT NULL REFERENCES member(id),
    status      varchar(20)  NOT NULL,
    verified_at timestamptz,
    ttl_days    int          NOT NULL DEFAULT 90,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);
CREATE INDEX idx_document_space_status ON document(space_id, status);
CREATE INDEX idx_document_status_verified ON document(status, verified_at);

CREATE TABLE block (
    id          uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    position    int  NOT NULL,
    type        varchar(30) NOT NULL,
    content     jsonb NOT NULL,
    source_type varchar(30) NOT NULL,
    source_url  varchar(2000),
    source_ref  varchar(200),
    updated_at  timestamptz NOT NULL
);
CREATE INDEX idx_block_document ON block(document_id, position);

CREATE TABLE revision (
    id          uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    snapshot    jsonb NOT NULL,
    editor_id   uuid NOT NULL REFERENCES member(id),
    cause       varchar(30) NOT NULL,
    created_at  timestamptz NOT NULL
);
CREATE INDEX idx_revision_document ON revision(document_id, created_at DESC);
