package com.agent.aiagent.domain.video.model;

import java.nio.file.Path;

public record VideoFrame(
        long timestampMillis,
        Path path
) {
}