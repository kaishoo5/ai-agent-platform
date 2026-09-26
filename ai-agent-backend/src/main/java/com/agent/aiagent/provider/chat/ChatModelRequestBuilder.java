package com.agent.aiagent.provider.chat;

import com.agent.aiagent.domain.tool.model.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatModelRequestBuilder {

    private static final String WEB_SEARCH_TOOL_NAME =
            "web_search";

    private static final String WEB_SEARCH_GROUNDING_PROMPT =
            """
            방금 제공된 web_search 결과를 근거로 사용자의 질문에 답변하세요.

            검색 결과에 포함된 각 SOURCE는 서로 독립된 출처입니다.

            최종 답변을 작성할 때 다음 원칙을 지키세요.

            - SOURCE의 TITLE, URL, CONTENT에서 직접 확인되는 사실만 사용하세요.
            - SOURCE에 없는 숫자, 날짜, 금액, 제품명, 기능명, 국가, 인물, 사건의 원인이나 결과를 추가하지 마세요.
            - 서로 다른 SOURCE의 내용을 결합하여 새로운 사실이나 관계를 만들지 마세요.
            - 하나의 SOURCE 안에서도 서로 다른 인물이나 기관의 행동을 하나의 주체가 한 행동처럼 합치지 마세요.
            - 검색 결과가 어떤 사실을 충분히 뒷받침하지 못하면 그 사실을 단정하지 마세요.
            - 검색 결과의 내용을 해석하거나 요약할 수는 있지만 원문보다 더 구체적인 사실을 새로 만들지 마세요.
            - 검색 결과와 모델의 기존 지식이 충돌하거나 모델의 기존 지식에만 존재하는 내용이 있다면 검색 결과를 우선하세요.
            - 사용자의 질문에 답하기에 현재 검색 결과가 충분하다면 추가 검색을 하지 말고 최종 답변을 작성하세요.
            """;

    public ChatModelRequest appendToolResults(
            ChatModelRequest request,
            List<ChatModelToolCall> toolCalls,
            List<ToolResult> toolResults
    ) {
        List<ChatModelMessage> messages =
                new ArrayList<>(
                        request.messages()
                );

        messages.add(
                new ChatModelMessage(
                        "assistant",
                        null,
                        null,
                        toolCalls,
                        null
                )
        );

        boolean webSearchExecuted =
                false;

        for (
                int index = 0;
                index < toolResults.size();
                index++
        ) {
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

            if (
                    result.success()
                            && WEB_SEARCH_TOOL_NAME.equals(
                            toolCall.name()
                    )
            ) {
                webSearchExecuted =
                        true;
            }
        }

        if (webSearchExecuted) {
            messages.add(
                    new ChatModelMessage(
                            "system",
                            WEB_SEARCH_GROUNDING_PROMPT,
                            null
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