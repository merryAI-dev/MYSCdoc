package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "space")
public class Space {
    @Id private UUID id;
    @Column(nullable = false, unique = true) private String slug;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private Instant createdAt;

    protected Space() {}

    public Space(String slug, String name) {
        this.id = UUID.randomUUID();
        this.slug = slug;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
