package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meetily_ingest_log")
public class MeetilyIngestLog {
    @Id private UUID id;
    @Column(nullable = false) private String meetingId;
    @Column(nullable = false) private UUID documentId;
    @Column(nullable = false) private Instant createdAt;

    protected MeetilyIngestLog() {}

    public MeetilyIngestLog(String meetingId, UUID documentId) {
        this.id = UUID.randomUUID();
        this.meetingId = meetingId;
        this.documentId = documentId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getMeetingId() { return meetingId; }
    public UUID getDocumentId() { return documentId; }
    public Instant getCreatedAt() { return createdAt; }
}
