package com.agent.aiagent.domain.rag.model;

public record ChatSource(
        String fileId,
        String fileName,
        String extension,
        int chunkIndex,
        Long startMillis,
        Long endMillis
) {
}