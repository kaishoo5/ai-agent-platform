package com.agent.aiagent.domain.video.model;

import java.util.List;

public record DramaEpisodeAnalysis(
        int episodeNumber,
        String fileId,
        String originalFileName,
        long sourceDurationMillis,
        List<VideoHighlightSegment> highlightCandidates
) {
    public DramaEpisodeAnalysis {
        highlightCandidates = List.copyOf(highlightCandidates);
    }
}
