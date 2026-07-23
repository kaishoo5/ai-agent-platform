package com.agent.aiagent.domain.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "chat_message")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @Column(name = "ID", length = 36, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ROOM_ID", nullable = false)
    private ChatRoom room;

    @Column(name = "ROLE", length = 20, nullable = false)
    private String role;

    @Column(name = "CONTENT", columnDefinition = "LONGTEXT", nullable = false)
    private String content;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ChatMessage(
            ChatRoom room,
            String role,
            String content
    ) {
        this.id = UUID.randomUUID().toString();
        this.room = room;
        this.role = role;
        this.content = content;
    }

    public void updateContent(String content) {
        this.content = content;
    }

    @PrePersist
    private void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }

        this.createdAt = LocalDateTime.now();
    }
}
