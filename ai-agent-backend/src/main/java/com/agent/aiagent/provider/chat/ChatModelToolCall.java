package com.agent.aiagent.provider.chat;

import java.util.Map;

public record ChatModelToolCall(
        String name,
        Map<String, Object> arguments,
        String thoughtSignature
) {

    public ChatModelToolCall {
        arguments = arguments == null
                ? Map.of()
                : Map.copyOf(arguments);
    }

    /**
     * 기존 Ollama 및 Tool Calling 코드와의 호환성을 유지한다.
     */
    public ChatModelToolCall(
            String name,
            Map<String, Object> arguments
    ) {
        this(name, arguments, null);
    }
}
