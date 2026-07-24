package com.agent.aiagent.domain.file.dto;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.entity.ChatFileStatus;

import java.time.LocalDateTime;

public record ChatFileResponse(
        String id,
        String roomId,
        String originalName,
        String contentType,
        String extension,
        long size,
        ChatFileStatus status,
        LocalDateTime createdAt
) {

    public static ChatFileResponse from(
            ChatFile chatFile
    ) {
        return new ChatFileResponse(
                chatFile.getId(),
                chatFile.getRoomId(),
                chatFile.getOriginalName(),
                chatFile.getContentType(),
                chatFile.getExtension(),
                chatFile.getSize(),
                chatFile.getStatus(),
                chatFile.getCreatedAt()
        );
    }
}