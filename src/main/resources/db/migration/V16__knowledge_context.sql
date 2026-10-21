-- Separate content dimensions from legacy extraction kind and factual status.
CREATE TABLE knowledge_context (
    triple_id uuid PRIMARY KEY REFERENCES knowledge_triple(id) ON DELETE CASCADE,
    classification jsonb NOT NULL,
    model varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
