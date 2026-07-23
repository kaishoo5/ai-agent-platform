package com.agent.aiagent.domain.chat.dto;

import com.agent.aiagent.domain.chat.entity.ChatRoom;

import java.time.LocalDateTime;

public record ChatRoomResponse(
        String id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ChatRoomResponse from(ChatRoom chatRoom) {
        return new ChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getTitle(),
                chatRoom.getCreatedAt(),
                chatRoom.getUpdatedAt()
        );
    }
}