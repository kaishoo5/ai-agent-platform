package com.agent.aiagent.domain.file.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@Entity
@Table(
        name = "chat_file_chunk",
        indexes = {
                @Index(
                        name = "idx_chat_file_chunk_file_id",
                        columnList = "file_id"
                ),
                @Index(
                        name = "idx_chat_file_chunk_room_file",
                        columnList = "room_id, file_id"
                )
        }
)
@NoArgsConstructor(
        access = AccessLevel.PROTECTED
)
@AllArgsConstructor
public class ChatFileChunk {

    @Id
    @Column(
            length = 36,
            nullable = false
    )
    private String id;

    @Column(
            name = "file_id",
            length = 36,
            nullable = false
    )
    private String fileId;

    @Column(
            name = "room_id",
            length = 36,
            nullable = false
    )
    private String roomId;

    @Column(
            name = "chunk_index",
            nullable = false
    )
    private int chunkIndex;

    @Column(
            columnDefinition = "LONGTEXT",
            nullable = false
    )
    private String content;

    @Column(
            columnDefinition = "LONGTEXT",
            nullable = false
    )
    private String embedding;

    @Column(
            name = "created_at",
            nullable = false
    )
    private LocalDateTime createdAt;
}