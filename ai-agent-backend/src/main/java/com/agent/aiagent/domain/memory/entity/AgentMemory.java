package com.agent.aiagent.domain.memory.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "agent_memory")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentMemory {

    @Id
    @Column(name = "ID", length = 36, nullable = false)
    private String id;

    @Column(name = "CATEGORY", length = 50, nullable = false)
    private String category;

    @Column(name = "CONTENT", length = 1000, nullable = false)
    private String content;

    @Column(name = "EMBEDDING", columnDefinition = "LONGTEXT", nullable = false)
    private String embedding;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    public AgentMemory(
            String category,
            String content,
            String embedding
    ) {
        this.id = UUID.randomUUID().toString();
        this.category = category;
        this.content = content;
        this.embedding = embedding;
    }

    public void update(
            String category,
            String content,
            String embedding
    ) {
        this.category = category;
        this.content = content;
        this.embedding = embedding;
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    private void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }

        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
