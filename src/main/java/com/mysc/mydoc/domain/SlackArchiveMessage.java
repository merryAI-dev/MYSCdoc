package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slack_archive_message")
public class SlackArchiveMessage {
    @Id private UUID id;
    @Column(nullable = false) private String channelId;
    @Column(nullable = false) private String ts;
    @Column(nullable = false) private String threadTs;
    @Column(nullable = false) private String userId;
    @Column(nullable = false) private String text;
    @Column(nullable = false) private Instant createdAt;

    protected SlackArchiveMessage() {}

    public SlackArchiveMessage(String channelId, String ts, String threadTs, String userId, String text) {
        this.id = UUID.randomUUID();
        this.channelId = channelId;
        this.ts = ts;
        this.threadTs = threadTs;
        this.userId = userId;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getChannelId() { return channelId; }
    public String getTs() { return ts; }
    public String getThreadTs() { return threadTs; }
    public String getUserId() { return userId; }
    public String getText() { return text; }
    public Instant getCreatedAt() { return createdAt; }
}
