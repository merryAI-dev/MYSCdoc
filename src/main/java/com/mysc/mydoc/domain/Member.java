package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "member")
public class Member {
    @Id private UUID id;
    @Column(nullable = false, unique = true) private String email;
    @Column(nullable = false) private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MemberRole role;
    private String slackUserId;
    @Column(nullable = false) private Instant createdAt;

    protected Member() {}

    public Member(String email, String displayName, MemberRole role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.displayName = displayName;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public MemberRole getRole() { return role; }
    public String getSlackUserId() { return slackUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setSlackUserId(String slackUserId) { this.slackUserId = slackUserId; }
}
