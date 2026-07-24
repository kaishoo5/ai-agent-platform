package com.agent.aiagent.domain.tool.service;

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

        if (
                toolCalls == null
                        || toolCalls.isEmpty()
        ) {
            return List.of();
        }

        return toolCalls.stream()
                .map(toolCall ->
                        toolExecutor.execute(
                                new ToolExecutionRequest(
                                        toolCall.name(),
                                        toolCall.arguments()
                                )
                        )
                )
                .toList();
    }
}