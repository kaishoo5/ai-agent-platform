package com.agent.aiagent.domain.video.model;

import java.util.List;

public record VideoHighlightSelection(
        long targetDurationMillis,
        List<VideoHighlightSegment> segments
) {
}