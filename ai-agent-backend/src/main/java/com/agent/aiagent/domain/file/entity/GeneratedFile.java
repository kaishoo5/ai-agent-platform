package com.agent.aiagent.domain.file.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "generated_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GeneratedFile {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "room_id", length = 36, nullable = false)
    private String roomId;

    @Column(name = "message_id", length = 36)
    private String messageId;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "stored_name", nullable = false)
    private String storedName;

    @Column(name = "stored_path", nullable = false, length = 1000)
    private String storedPath;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "extension", length = 20, nullable = false)
    private String extension;

    @Column(name = "size", nullable = false)
    private long size;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public GeneratedFile(
            String roomId,
            String originalName,
            String storedName,
            String storedPath,
            String contentType,
            String extension,
            long size
    ) {
        this.id = UUID.randomUUID().toString();
        this.roomId = roomId;
        this.originalName = originalName;
        this.storedName = storedName;
        this.storedPath = storedPath;
        this.contentType = contentType;
        this.extension = extension;
        this.size = size;
    }

    public void attachMessage(
            String messageId
    ) {
        this.messageId = messageId;
    }

    @PrePersist
    private void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }

        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}