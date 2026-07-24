package com.agent.aiagent.domain.file.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatFile {

    @Id
    @Column(
            name = "id",
            length = 36
    )
    private String id;

    @Column(
            name = "room_id",
            nullable = false,
            length = 36
    )
    private String roomId;

    @Column(
            name = "original_name",
            nullable = false
    )
    private String originalName;

    @Column(
            name = "stored_name",
            nullable = false
    )
    private String storedName;

    @Column(
            name = "stored_path",
            nullable = false,
            length = 1000
    )
    private String storedPath;

    @Column(
            name = "content_type",
            length = 100
    )
    private String contentType;

    @Column(
            name = "extension",
            length = 20
    )
    private String extension;

    @Column(
            name = "size",
            nullable = false
    )
    private long size;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private ChatFileStatus status;

    @Column(
            name = "created_at",
            nullable = false
    )
    private LocalDateTime createdAt;

    public ChatFile(
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
        this.status = ChatFileStatus.UPLOADED;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}