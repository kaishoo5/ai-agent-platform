package com.agent.aiagent.provider.chat;

import lombok.Getter;

import java.util.List;

@Getter
public class ChatModelMessage {

    private final String role;
    private final String content;
    private final List<String> images;
    private final List<ChatModelToolCall> toolCalls;
    private final String toolName;

    public ChatModelMessage(
            String role,
            String content
    ) {
        this(
                role,
                content,
                null,
                List.of(),
                null
        );
    }

    public ChatModelMessage(
            String role,
            String content,
            List<String> images
    ) {
        this(
                role,
                content,
                images,
                List.of(),
                null
        );
    }

    public ChatModelMessage(
            String role,
            String content,
            List<String> images,
            List<ChatModelToolCall> toolCalls,
            String toolName
    ) {
        this.role = role;
        this.content = content;
        this.images = images;
        this.toolCalls =
                toolCalls == null
                        ? List.of()
                        : List.copyOf(toolCalls);
        this.toolName = toolName;
    }
}