package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "google_drive_ingest_log")
public class GoogleDriveIngestLog {
    @Id private UUID id;
    @Column(nullable = false) private String driveFileId;
    @Column(nullable = false) private UUID documentId;
    @Column(nullable = false) private Instant createdAt;

    protected GoogleDriveIngestLog() {}

    public GoogleDriveIngestLog(String driveFileId, UUID documentId) {
        this.id = UUID.randomUUID();
        this.driveFileId = driveFileId;
        this.documentId = documentId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getDriveFileId() { return driveFileId; }
    public UUID getDocumentId() { return documentId; }
    public Instant getCreatedAt() { return createdAt; }
}
