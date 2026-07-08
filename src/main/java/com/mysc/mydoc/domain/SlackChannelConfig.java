package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "slack_channel_config")
public class SlackChannelConfig {
    @Id private String channelId;
    @Column(nullable = false) private String channelName;
    @Column(nullable = false) private boolean archiveEnabled;
    @Column(nullable = false) private Instant updatedAt;

    protected SlackChannelConfig() {}

    public SlackChannelConfig(String channelId, String channelName, boolean archiveEnabled) {
        this.channelId = channelId;
        this.channelName = channelName;
        this.archiveEnabled = archiveEnabled;
        this.updatedAt = Instant.now();
    }

    public void update(String channelName, boolean archiveEnabled) {
        this.channelName = channelName;
        this.archiveEnabled = archiveEnabled;
        this.updatedAt = Instant.now();
    }

    public String getChannelId() { return channelId; }
    public String getChannelName() { return channelName; }
    public boolean isArchiveEnabled() { return archiveEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
