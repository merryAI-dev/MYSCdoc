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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document")
public class Document {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "space_id") private Space space;
    @Column(nullable = false) private String title;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_id") private Member owner;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DocStatus status;
    private Instant verifiedAt;
    @Column(nullable = false) private int ttlDays;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected Document() {}

    public Document(Space space, String title, Member owner) {
        this.id = UUID.randomUUID();
        this.space = space;
        this.title = title;
        this.owner = owner;
        this.status = DocStatus.DRAFT;
        this.ttlDays = 90;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void rename(String title) { this.title = title; touch(); }
    public void activate() { this.status = DocStatus.ACTIVE; touch(); }
    public void archive() { this.status = DocStatus.ARCHIVED; touch(); }
    public void markStale() { this.status = DocStatus.STALE; touch(); }
    public void verify() { this.verifiedAt = Instant.now(); if (status == DocStatus.STALE) status = DocStatus.ACTIVE; touch(); }
    public void changeOwner(Member newOwner) { this.owner = newOwner; touch(); }
    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public String getTitle() { return title; }
    public Member getOwner() { return owner; }
    public DocStatus getStatus() { return status; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public int getTtlDays() { return ttlDays; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
