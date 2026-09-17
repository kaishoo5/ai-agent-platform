package com.agent.aiagent.domain.file.model;

public record FileAnalysisStep(
        String code,
        String status,
        String message
) {

    public static FileAnalysisStep running(
            String code,
            String message
    ) {
        return new FileAnalysisStep(
                code,
                "running",
                message
        );
    }

    public static FileAnalysisStep completed(
            String code,
            String message
    ) {
        return new FileAnalysisStep(
                code,
                "completed",
                message
        );
    }

    public static FileAnalysisStep failed(
            String code,
            String message
    ) {
        return new FileAnalysisStep(
                code,
                "failed",
                message
        );
    }
}