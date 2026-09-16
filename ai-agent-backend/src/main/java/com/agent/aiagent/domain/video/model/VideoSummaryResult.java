package com.agent.aiagent.domain.video.model;

public record VideoSummaryResult(
        String fileId,
        String fileName,
        long durationSeconds,
        String streamUrl,
        String downloadUrl
) {
}