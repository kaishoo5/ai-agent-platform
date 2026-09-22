package com.agent.aiagent.domain.tool.model;

import java.util.List;

public record ToolExecutionContext(
        String roomId,
        List<String> fileIds
) {

    public ToolExecutionContext {
        fileIds =
                fileIds == null
                        ? List.of()
                        : List.copyOf(fileIds);
    }

    public static ToolExecutionContext empty() {
        return new ToolExecutionContext(
                null,
                List.of()
        );
    }
}