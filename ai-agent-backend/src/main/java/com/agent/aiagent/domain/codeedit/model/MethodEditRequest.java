package com.agent.aiagent.domain.codeedit.model;

public record MethodEditRequest(
        String className,
        String methodName,
        String instruction,
        String path
) {
}