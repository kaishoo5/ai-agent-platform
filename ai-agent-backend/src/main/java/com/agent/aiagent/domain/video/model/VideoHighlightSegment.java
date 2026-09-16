package com.agent.aiagent.domain.video.model;

public record VideoHighlightSegment(
        long startMillis,
        long endMillis,
        String reason
) {
}