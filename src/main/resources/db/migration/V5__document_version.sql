-- Optimistic-lock version for document status transitions (verify vs staleness job).
ALTER TABLE document ADD COLUMN version bigint NOT NULL DEFAULT 0;
