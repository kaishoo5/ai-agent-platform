package com.agent.aiagent.domain.video.model;

public record VideoResult(
        String fileId,
        String fileName,
        long durationSeconds,
        String streamUrl,
        String downloadUrl
) {
}