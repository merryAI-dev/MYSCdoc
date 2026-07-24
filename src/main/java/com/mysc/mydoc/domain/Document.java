package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document")
public class Document {
    private static final int DEFAULT_TTL_DAYS = 90; // 04-database.md

    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "space_id") private Space space;
    @Column(nullable = false) private String title;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_id") private Member owner;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DocStatus status;
    private Instant verifiedAt;
    // 사건 시각(회의일·메시지 시각) — 임포트 시각(createdAt)과 별개. 시간축 분석의 기준.
    private Instant eventAt;
    @Column(nullable = false) private int ttlDays;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    // Guards status transitions (verify vs staleness job) against silent lost updates.
    @Version @Column(nullable = false) private long version;

    protected Document() {}

    public Document(Space space, String title, Member owner) {
        this.id = UUID.randomUUID();
        this.space = space;
        this.title = title;
        this.owner = owner;
        this.status = DocStatus.DRAFT;
        this.ttlDays = DEFAULT_TTL_DAYS;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void rename(String title) { this.title = title; touch(); }
    public void activate() { this.status = DocStatus.ACTIVE; touch(); }
    public void archive() { this.status = DocStatus.ARCHIVED; touch(); }
    public void markStale() { this.status = DocStatus.STALE; touch(); }
    public void verify() { this.verifiedAt = Instant.now(); if (status == DocStatus.STALE) status = DocStatus.ACTIVE; touch(); }
    public void changeOwner(Member newOwner) { this.owner = newOwner; touch(); }
    /** 사건 시각 지정 — 원본이 알려준 회의일/메시지 시각. updatedAt은 건드리지 않는다. */
    public void setEventAt(Instant eventAt) { this.eventAt = eventAt; }
    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public String getTitle() { return title; }
    public Member getOwner() { return owner; }
    public DocStatus getStatus() { return status; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getEventAt() { return eventAt; }
    public int getTtlDays() { return ttlDays; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
