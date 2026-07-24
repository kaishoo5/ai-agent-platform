package com.agent.aiagent.provider.chat;

import lombok.Getter;

import java.util.List;

@Getter
public class ChatModelMessage {

    private final String role;
    private final String content;
    private final List<String> images;

    public ChatModelMessage(
            String role,
            String content
    ) {
        this(
                role,
                content,
                null
        );
    }

    public ChatModelMessage(
            String role,
            String content,
            List<String> images
    ) {
        this.role = role;
        this.content = content;
        this.images = images;
    }
}