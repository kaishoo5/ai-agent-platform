package com.agent.aiagent.provider.chat;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelToolTest {

    @Test
    void Tool_모델을_생성한다() {
        ChatModelTool tool =
                new ChatModelTool(
                        "current_time",
                        "지정한 시간대의 현재 시간을 조회합니다.",
                        Map.of(
                                "zoneId",
                                new ChatModelToolParameter(
                                        "string",
                                        "IANA 시간대 ID",
                                        false
                                )
                        )
                );

        assertThat(tool.name())
                .isEqualTo(
                        "current_time"
                );

        assertThat(tool.description())
                .isNotBlank();

        assertThat(tool.parameters())
                .containsKey(
                        "zoneId"
                );
    }

    @Test
    void parameters가_null이면_빈_Map으로_변환한다() {
        ChatModelTool tool =
                new ChatModelTool(
                        "current_time",
                        "현재 시간을 조회합니다.",
                        null
                );

        assertThat(tool.parameters())
                .isEmpty();
    }

    @Test
    void parameters는_외부에서_변경할_수_없다() {
        Map<String, ChatModelToolParameter> parameters =
                new HashMap<>();

        parameters.put(
                "zoneId",
                new ChatModelToolParameter(
                        "string",
                        "IANA 시간대 ID",
                        false
                )
        );

        ChatModelTool tool =
                new ChatModelTool(
                        "current_time",
                        "현재 시간을 조회합니다.",
                        parameters
                );

        parameters.clear();

        assertThat(tool.parameters())
                .containsKey(
                        "zoneId"
                );

        assertThatThrownBy(
                () -> tool.parameters()
                        .put(
                                "format",
                                new ChatModelToolParameter(
                                        "string",
                                        "출력 형식",
                                        false
                                )
                        )
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }
}