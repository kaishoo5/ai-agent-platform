package com.agent.aiagent.domain.chat.dto;

import com.agent.aiagent.domain.chat.entity.ChatMessage;
import com.agent.aiagent.domain.file.dto.GeneratedFileResponse;
import com.agent.aiagent.domain.file.entity.GeneratedFile;

import java.time.LocalDateTime;
import java.util.List;

public record ChatMessageResponse(
        String id,
        String roomId,
        String role,
        String content,
        String videoResult,
        String sourceResult,
        List<GeneratedFileResponse> generatedFiles,
        LocalDateTime createdAt
) {

    public static ChatMessageResponse from(
            ChatMessage chatMessage
    ) {
        return from(
                chatMessage,
                List.of()
        );
    }

    public static ChatMessageResponse from(
            ChatMessage chatMessage,
            List<GeneratedFile> generatedFiles
    ) {
        List<GeneratedFileResponse> generatedFileResponses =
                generatedFiles == null
                        ? List.of()
                        : generatedFiles
                        .stream()
                        .map(
                                GeneratedFileResponse::from
                        )
                        .toList();

        return new ChatMessageResponse(
                chatMessage.getId(),
                chatMessage.getRoom().getId(),
                chatMessage.getRole(),
                chatMessage.getContent(),
                chatMessage.getVideoResult(),
                chatMessage.getSourceResult(),
                generatedFileResponses,
                chatMessage.getCreatedAt()
        );
    }
}