package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tiro_ingest_log")
public class TiroIngestLog {
    @Id private UUID id;
    @Column(nullable = false) private String noteGuid;
    @Column(nullable = false) private UUID documentId;
    @Column(nullable = false) private Instant createdAt;

    protected TiroIngestLog() {}

    public TiroIngestLog(String noteGuid, UUID documentId) {
        this.id = UUID.randomUUID();
        this.noteGuid = noteGuid;
        this.documentId = documentId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getNoteGuid() { return noteGuid; }
    public UUID getDocumentId() { return documentId; }
    public Instant getCreatedAt() { return createdAt; }
}
