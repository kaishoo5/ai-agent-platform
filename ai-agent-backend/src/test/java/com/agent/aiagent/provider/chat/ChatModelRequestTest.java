package com.agent.aiagent.provider.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelRequestTest {

    @Test
    void Tool_목록을_보관한다() {

        ChatModelTool chatModelTool =
                new ChatModelTool(
                        "current_time",
                        "현재 시간을 조회합니다.",
                        Map.of()
                );

        ChatModelRequest chatModelRequest =
                new ChatModelRequest(
                        ChatModelType.TEXT,
                        List.of(),
                        List.of(
                                chatModelTool
                        )
                );

        assertThat(
                chatModelRequest.tools()
        ).hasSize(
                1
        );

        assertThat(
                chatModelRequest.tools()
                        .getFirst()
                        .name()
        ).isEqualTo(
                "current_time"
        );
    }

    @Test
    void messages와_tools가_null이면_빈_목록으로_변환한다() {

        ChatModelRequest chatModelRequest =
                new ChatModelRequest(
                        ChatModelType.TEXT,
                        null,
                        null
                );

        assertThat(
                chatModelRequest.messages()
        ).isEmpty();

        assertThat(
                chatModelRequest.tools()
        ).isEmpty();
    }
}