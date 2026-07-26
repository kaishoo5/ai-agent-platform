package com.agent.aiagent.provider.chat;

import com.agent.aiagent.domain.tool.model.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatModelRequestBuilder {

    public ChatModelRequest appendToolResults(
            ChatModelRequest request,
            List<ChatModelToolCall> toolCalls,
            List<ToolResult> toolResults
    ) {

        List<ChatModelMessage> messages =
                new ArrayList<>(request.messages());

        messages.add(
                new ChatModelMessage(
                        "assistant",
                        null,
                        null,
                        toolCalls,
                        null
                )
        );

        for (int index = 0; index < toolResults.size(); index++) {

            ToolResult result =
                    toolResults.get(index);

            ChatModelToolCall toolCall =
                    toolCalls.get(index);

            messages.add(
                    new ChatModelMessage(
                            "tool",
                            result.content(),
                            null,
                            List.of(),
                            toolCall.name()
                    )
            );
        }

        return new ChatModelRequest(
                request.modelType(),
                messages,
                request.tools()
        );
    }

}