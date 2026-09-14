package com.agent.aiagent.domain.codeedit.model;

public record CodeBuildValidationResult(
        boolean success,
        String message
) {

    public static CodeBuildValidationResult success(
            String message
    ) {
        return new CodeBuildValidationResult(
                true,
                message
        );
    }

    public static CodeBuildValidationResult fail(
            String message
    ) {
        return new CodeBuildValidationResult(
                false,
                message
        );
    }
}