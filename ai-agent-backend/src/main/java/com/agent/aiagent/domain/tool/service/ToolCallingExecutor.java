package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.service.ChatStreamingExecutor;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@RequiredArgsConstructor
public class ToolCallingExecutor {

    private final ChatStreamingExecutor chatStreamingExecutor;

    public SseEmitter execute(
            ChatRequest request,
            ChatModelRequest chatModelRequest
    ) {

        // TODO Tool Calling Loop

        return chatStreamingExecutor.execute(
                request,
                chatModelRequest
        );
    }

}