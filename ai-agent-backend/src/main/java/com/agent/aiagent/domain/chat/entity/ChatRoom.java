package com.agent.aiagent.domain.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "chat_room")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

    @Id
    @Column(name = "ID", length = 36, nullable = false)
    private String id;

    @Column(name = "TITLE", length = 200, nullable = false)
    private String title;

    @Column(name = "SUMMARY", columnDefinition = "LONGTEXT")
    private String summary;

    @Column(name = "SUMMARY_UPDATED_AT")
    private LocalDateTime summaryUpdatedAt;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    public ChatRoom(String title) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
    }

    public void updateSummary(
            String summary
    ) {
        this.summary = summary;
        this.summaryUpdatedAt = LocalDateTime.now();
    }

    public void updateTitle(String title) {
        this.title = title;
        this.updatedAt = LocalDateTime.now();
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public void changeTitle(
            String title
    ) {
        this.title = title;
    }

    @PrePersist
    private void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }

        if (this.title == null || this.title.isBlank()) {
            this.title = "새 채팅";
        }

        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
