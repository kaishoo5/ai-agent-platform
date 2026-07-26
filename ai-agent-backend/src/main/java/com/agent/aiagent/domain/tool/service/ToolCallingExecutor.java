package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.service.ChatStreamingExecutor;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelRequestBuilder;
import com.agent.aiagent.provider.chat.ChatModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallingExecutor {

    private static final int MAX_TOOL_CALL_ROUNDS = 5;

    private final ChatModelProvider chatModelProvider;
    private final ToolCallProcessor toolCallProcessor;
    private final ChatModelRequestBuilder chatModelRequestBuilder;
    private final ChatStreamingExecutor chatStreamingExecutor;

    public SseEmitter execute(
            ChatRequest request,
            ChatModelRequest chatModelRequest
    ) {

        if (chatModelRequest.tools().isEmpty()) {
            return chatStreamingExecutor.execute(
                    request,
                    chatModelRequest
            );
        }

        ChatModelRequest currentRequest =
                chatModelRequest;

        for (
                int round = 1;
                round <= MAX_TOOL_CALL_ROUNDS;
                round++
        ) {
            ChatModelResponse response =
                    chatModelProvider.chatOnce(
                            currentRequest
                    );

            if (!response.hasToolCalls()) {
                log.debug(
                        "Tool Calling 종료. round={}",
                        round
                );

                return chatStreamingExecutor.execute(
                        request,
                        currentRequest
                );
            }

            log.info(
                    "Tool Calling 실행. round={}, toolCallCount={}, tools={}",
                    round,
                    response.toolCalls().size(),
                    response.toolCalls()
                            .stream()
                            .map(toolCall ->
                                    toolCall.name()
                            )
                            .toList()
            );

            List<ToolResult> toolResults =
                    toolCallProcessor.execute(
                            response.toolCalls()
                    );

            currentRequest =
                    chatModelRequestBuilder.appendToolResults(
                            currentRequest,
                            response.toolCalls(),
                            toolResults
                    );
        }

        log.warn(
                "Tool Calling 최대 반복 횟수에 도달했습니다. maxRounds={}",
                MAX_TOOL_CALL_ROUNDS
        );

        return chatStreamingExecutor.execute(
                request,
                currentRequest
        );
    }
}