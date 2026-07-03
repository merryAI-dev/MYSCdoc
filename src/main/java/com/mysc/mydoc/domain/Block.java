package com.mysc.mydoc.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "block")
public class Block {
    @Id private UUID id;
    @Column(name = "document_id", nullable = false) private UUID documentId;
    @Column(nullable = false) private int position;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private BlockType type;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private JsonNode content;
    @Embedded private Provenance provenance;
    @Column(nullable = false) private Instant updatedAt;

    protected Block() {}

    public Block(UUID documentId, int position, BlockType type, JsonNode content, Provenance provenance) {
        this.id = UUID.randomUUID();
        this.documentId = documentId;
        this.position = position;
        this.type = type;
        this.content = content;
        this.provenance = provenance;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public int getPosition() { return position; }
    public BlockType getType() { return type; }
    public JsonNode getContent() { return content; }
    public Provenance getProvenance() { return provenance; }
    public Instant getUpdatedAt() { return updatedAt; }
}
