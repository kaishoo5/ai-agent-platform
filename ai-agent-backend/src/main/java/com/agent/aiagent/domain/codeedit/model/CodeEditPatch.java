package com.agent.aiagent.domain.codeedit.model;

public record CodeEditPatch(
        String type,
        String className,
        String methodName,
        String code,
        String path
) {
}