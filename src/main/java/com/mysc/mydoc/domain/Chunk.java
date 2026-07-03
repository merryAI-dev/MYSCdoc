package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chunk")
public class Chunk {
    @Id private UUID id;
    @Column(name = "document_id", nullable = false) private UUID documentId;
    @Column(nullable = false) private String headingPath;
    @Column(nullable = false, columnDefinition = "text") private String text;
    @Array(length = 1536) @JdbcTypeCode(SqlTypes.VECTOR) @Column(columnDefinition = "vector(1536)")
    private float[] embedding;
    @Column(nullable = false) private Instant createdAt;

    protected Chunk() {}

    public Chunk(UUID documentId, String headingPath, String text, float[] embedding) {
        this.id = UUID.randomUUID();
        this.documentId = documentId;
        this.headingPath = headingPath;
        this.text = text;
        this.embedding = embedding;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public String getHeadingPath() { return headingPath; }
    public String getText() { return text; }
    public float[] getEmbedding() { return embedding; }
    public Instant getCreatedAt() { return createdAt; }
}
