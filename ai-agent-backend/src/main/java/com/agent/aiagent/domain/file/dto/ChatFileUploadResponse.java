package com.agent.aiagent.domain.file.dto;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.entity.ChatFileStatus;

public record ChatFileUploadResponse(
        String id,
        String roomId,
        String originalName,
        String contentType,
        String extension,
        long size,
        ChatFileStatus status
) {

    public static ChatFileUploadResponse from(
            ChatFile chatFile
    ) {
        return new ChatFileUploadResponse(
                chatFile.getId(),
                chatFile.getRoomId(),
                chatFile.getOriginalName(),
                chatFile.getContentType(),
                chatFile.getExtension(),
                chatFile.getSize(),
                chatFile.getStatus()
        );
    }
}