package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolExecutionRequest;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.provider.chat.ChatModelToolCall;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ToolCallProcessor {

    private final ToolExecutor toolExecutor;

    public List<ToolResult> execute(
            List<ChatModelToolCall> toolCalls
    ) {
        return execute(
                toolCalls,
                ToolExecutionContext.empty()
        );
    }

    public List<ToolResult> execute(
            List<ChatModelToolCall> toolCalls,
            ToolExecutionContext context
    ) {
        if (
                toolCalls == null
                        || toolCalls.isEmpty()
        ) {
            return List.of();
        }

        ToolExecutionContext safeContext =
                context == null
                        ? ToolExecutionContext.empty()
                        : context;

        return toolCalls.stream()
                .map(toolCall ->
                        toolExecutor.execute(
                                new ToolExecutionRequest(
                                        toolCall.name(),
                                        toolCall.arguments(),
                                        safeContext
                                )
                        )
                )
                .toList();
    }
}