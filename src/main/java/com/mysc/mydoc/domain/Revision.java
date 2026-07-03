package com.mysc.mydoc.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
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
@Table(name = "revision")
public class Revision {
    @Id private UUID id;
    @Column(name = "document_id", nullable = false) private UUID documentId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private JsonNode snapshot;
    @Column(name = "editor_id", nullable = false) private UUID editorId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ChangeCause cause;
    @Column(nullable = false) private Instant createdAt;

    protected Revision() {}

    public Revision(UUID documentId, JsonNode snapshot, UUID editorId, ChangeCause cause) {
        this.id = UUID.randomUUID();
        this.documentId = documentId;
        this.snapshot = snapshot;
        this.editorId = editorId;
        this.cause = cause;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public JsonNode getSnapshot() { return snapshot; }
    public UUID getEditorId() { return editorId; }
    public ChangeCause getCause() { return cause; }
    public Instant getCreatedAt() { return createdAt; }
}
