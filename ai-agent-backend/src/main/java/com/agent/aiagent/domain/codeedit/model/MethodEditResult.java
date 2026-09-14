package com.agent.aiagent.domain.codeedit.model;

public record MethodEditResult(
        boolean success,
        String message,
        MethodEditPatch patch
) {

    public static MethodEditResult success(
            String message,
            MethodEditPatch patch
    ) {
        return new MethodEditResult(
                true,
                message,
                patch
        );
    }

    public static MethodEditResult fail(
            String message
    ) {
        return new MethodEditResult(
                false,
                message,
                null
        );
    }
}