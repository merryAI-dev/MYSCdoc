package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slack_ingest_log")
public class SlackIngestLog {
    @Id private UUID id;
    @Column(nullable = false) private String channelId;
    @Column(nullable = false) private String threadTs;
    @Column(nullable = false) private UUID documentId;
    @Column(nullable = false) private Instant createdAt;

    protected SlackIngestLog() {}

    public SlackIngestLog(String channelId, String threadTs, UUID documentId) {
        this.id = UUID.randomUUID();
        this.channelId = channelId;
        this.threadTs = threadTs;
        this.documentId = documentId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getChannelId() { return channelId; }
    public String getThreadTs() { return threadTs; }
    public UUID getDocumentId() { return documentId; }
    public Instant getCreatedAt() { return createdAt; }
}
