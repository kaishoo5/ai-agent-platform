package com.agent.aiagent.domain.file.dto;

import com.agent.aiagent.domain.file.entity.ChatFile;

public record ChatFileUploadResponse(
        String id,
        String roomId,
        String originalName,
        String contentType,
        String extension,
        long size
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
                chatFile.getSize()
        );
    }
}