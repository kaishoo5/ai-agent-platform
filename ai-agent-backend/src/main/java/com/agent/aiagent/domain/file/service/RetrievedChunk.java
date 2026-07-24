package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;

public record RetrievedChunk(
        ChatFileChunk chunk,
        double embeddingScore,
        double keywordScore,
        double finalScore
) {
}