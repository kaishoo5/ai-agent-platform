package com.agent.aiagent.domain.video.model;

public record DramaEditSegment(
        int episodeNumber,
        String fileId,
        long startMillis,
        long endMillis,
        String reason
) {
    public long durationMillis() {
        return endMillis - startMillis;
    }
}
