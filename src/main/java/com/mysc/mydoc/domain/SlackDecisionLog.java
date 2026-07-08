package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slack_decision_log")
public class SlackDecisionLog {
    @Id private UUID id;
    @Column(nullable = false) private String channelId;
    @Column(nullable = false) private String threadTs;
    @Column(nullable = false) private String lastTs;
    private UUID documentId;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected SlackDecisionLog() {}

    public SlackDecisionLog(String channelId, String threadTs, String lastTs) {
        this.id = UUID.randomUUID();
        this.channelId = channelId;
        this.threadTs = threadTs;
        this.lastTs = lastTs;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void examined(String lastTs) {
        this.lastTs = lastTs;
        this.updatedAt = Instant.now();
    }

    public void linkDocument(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getId() { return id; }
    public String getChannelId() { return channelId; }
    public String getThreadTs() { return threadTs; }
    public String getLastTs() { return lastTs; }
    public UUID getDocumentId() { return documentId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
