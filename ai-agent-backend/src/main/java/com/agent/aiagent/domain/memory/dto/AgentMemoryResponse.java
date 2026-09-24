package com.agent.aiagent.domain.memory.dto;

import com.agent.aiagent.domain.memory.entity.AgentMemory;

import java.time.LocalDateTime;

public record AgentMemoryResponse(
        String id,
        String category,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AgentMemoryResponse from(
            AgentMemory memory
    ) {
        return new AgentMemoryResponse(
                memory.getId(),
                memory.getCategory(),
                memory.getContent(),
                memory.getCreatedAt(),
                memory.getUpdatedAt()
        );
    }
}
