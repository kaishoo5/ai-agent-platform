package com.agent.aiagent.domain.video.model;

public record VideoTranscriptSegment(
        long startMillis,
        long endMillis,
        String text
) {
}