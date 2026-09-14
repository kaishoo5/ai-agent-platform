package com.agent.aiagent.domain.video.model;

import java.util.List;

public record VideoTranscript(
        String language,
        String text,
        List<VideoTranscriptSegment> segments
) {
}