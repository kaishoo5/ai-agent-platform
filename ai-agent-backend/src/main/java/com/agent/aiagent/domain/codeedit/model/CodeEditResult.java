package com.agent.aiagent.domain.codeedit.model;

import java.util.List;

public record CodeEditResult(
        boolean success,
        String message,
        List<CodeEditPatch> patches
) {

    public static CodeEditResult success(
            String message,
            List<CodeEditPatch> patches
    ) {
        return new CodeEditResult(
                true,
                message,
                patches
        );
    }

    public static CodeEditResult fail(
            String message
    ) {
        return new CodeEditResult(
                false,
                message,
                List.of()
        );
    }
}