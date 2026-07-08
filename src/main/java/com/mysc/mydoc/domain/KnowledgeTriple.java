package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Slack 스레드에서 추출된 지식 온톨로지의 최소 단위 — (주어, 서술어, 목적어) 트리플.
 * kind: decision(의사결정) 또는 암묵지 분류(policy/constraint/workaround/gotcha/convention/risk).
 */
@Entity
@Table(name = "knowledge_triple")
public class KnowledgeTriple {
    @Id private UUID id;
    @Column(nullable = false) private UUID documentId;
    @Column(nullable = false) private String kind;
    @Column(nullable = false) private String statement;
    @Column(nullable = false) private String subject;
    @Column(nullable = false) private String predicate;
    @Column(name = "object", nullable = false) private String object;
    @Column(nullable = false) private String channelId;
    @Column(nullable = false) private String threadTs;
    @Column(nullable = false) private Instant createdAt;

    protected KnowledgeTriple() {}

    public KnowledgeTriple(UUID documentId, String kind, String statement,
                           String subject, String predicate, String object,
                           String channelId, String threadTs) {
        this.id = UUID.randomUUID();
        this.documentId = documentId;
        this.kind = kind;
        this.statement = statement;
        this.subject = subject;
        this.predicate = predicate;
        this.object = object;
        this.channelId = channelId;
        this.threadTs = threadTs;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public String getKind() { return kind; }
    public String getStatement() { return statement; }
    public String getSubject() { return subject; }
    public String getPredicate() { return predicate; }
    public String getObject() { return object; }
    public String getChannelId() { return channelId; }
    public String getThreadTs() { return threadTs; }
    public Instant getCreatedAt() { return createdAt; }
}
