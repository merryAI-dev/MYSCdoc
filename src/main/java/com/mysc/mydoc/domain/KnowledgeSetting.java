package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 의사결정 추출 잡의 미세조정 값 — 단일 행(id=1). */
@Entity
@Table(name = "knowledge_setting")
public class KnowledgeSetting {
    public static final int SINGLETON_ID = 1;

    @Id private Integer id;
    @Column(nullable = false) private int quietMinutes;
    @Column(nullable = false) private int minMessages;
    @Column(nullable = false) private Instant updatedAt;

    protected KnowledgeSetting() {}

    public KnowledgeSetting(int quietMinutes, int minMessages) {
        this.id = SINGLETON_ID;
        this.quietMinutes = quietMinutes;
        this.minMessages = minMessages;
        this.updatedAt = Instant.now();
    }

    public void update(int quietMinutes, int minMessages) {
        this.quietMinutes = quietMinutes;
        this.minMessages = minMessages;
        this.updatedAt = Instant.now();
    }

    public Integer getId() { return id; }
    public int getQuietMinutes() { return quietMinutes; }
    public int getMinMessages() { return minMessages; }
    public Instant getUpdatedAt() { return updatedAt; }
}
