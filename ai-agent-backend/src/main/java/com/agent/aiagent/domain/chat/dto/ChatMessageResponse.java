package com.agent.aiagent.domain.chat.dto;

import com.agent.aiagent.domain.chat.entity.ChatMessage;

import java.time.LocalDateTime;

public record ChatMessageResponse(
        String id,
        String roomId,
        String role,
        String content,
        LocalDateTime createdAt
) {

    public static ChatMessageResponse from(ChatMessage chatMessage) {
        return new ChatMessageResponse(
                chatMessage.getId(),
                chatMessage.getRoom().getId(),
                chatMessage.getRole(),
                chatMessage.getContent(),
                chatMessage.getCreatedAt()
        );
    }
}