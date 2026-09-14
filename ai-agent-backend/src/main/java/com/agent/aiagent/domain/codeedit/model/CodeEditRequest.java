package com.agent.aiagent.domain.codeedit.model;

public record CodeEditRequest(
        String instruction,
        String path
) {
}