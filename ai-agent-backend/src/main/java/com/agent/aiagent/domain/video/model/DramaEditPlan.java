package com.agent.aiagent.domain.video.model;

import java.util.List;

public record DramaEditPlan(
        DramaSummaryMode mode,
        long targetDurationMillis,
        long plannedDurationMillis,
        List<DramaEditSegment> segments
) {
    public DramaEditPlan {
        segments = List.copyOf(segments);
    }
}
