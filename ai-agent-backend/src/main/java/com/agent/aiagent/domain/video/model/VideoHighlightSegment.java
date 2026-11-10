package com.agent.aiagent.domain.video.model;

public record VideoHighlightSegment(
        long startMillis,
        long endMillis,
        String reason,
        String suggestedTitle
) {
    public VideoHighlightSegment(long startMillis, long endMillis, String reason) {
        this(startMillis, endMillis, reason, null);
    }
}
