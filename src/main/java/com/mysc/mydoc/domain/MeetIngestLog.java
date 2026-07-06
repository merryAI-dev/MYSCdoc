package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meet_ingest_log")
public class MeetIngestLog {
    @Id private UUID id;
    @Column(nullable = false, unique = true) private String conferenceRecord;
    @Column(nullable = false) private UUID documentId;
    @Column(nullable = false) private Instant createdAt;

    protected MeetIngestLog() {}

    public MeetIngestLog(String conferenceRecord, UUID documentId) {
        this.id = UUID.randomUUID();
        this.conferenceRecord = conferenceRecord;
        this.documentId = documentId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getConferenceRecord() { return conferenceRecord; }
    public UUID getDocumentId() { return documentId; }
    public Instant getCreatedAt() { return createdAt; }
}
