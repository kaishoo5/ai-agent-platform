package com.agent.aiagent.domain.codeedit.model;

public record MethodEditPatch(
        String type,
        String className,
        String methodName,
        String code
) {
}