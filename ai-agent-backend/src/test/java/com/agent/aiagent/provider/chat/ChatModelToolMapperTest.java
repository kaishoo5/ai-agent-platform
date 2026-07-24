package com.agent.aiagent.provider.chat;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelToolMapperTest {

    private final ChatModelToolMapper chatModelToolMapper =
            new ChatModelToolMapper();

    @Test
    void ToolSpecification을_ChatModelTool로_변환한다() {

        ToolSpecification toolSpecification =
                new ToolSpecification(
                        "current_time",
                        "현재 시간을 조회합니다.",
                        Map.of(
                                "zoneId",
                                new ToolParameter(
                                        "string",
                                        "IANA 시간대 ID",
                                        false
                                )
                        )
                );

        ChatModelTool chatModelTool =
                chatModelToolMapper.map(
                        toolSpecification
                );

        assertThat(
                chatModelTool.name()
        ).isEqualTo(
                "current_time"
        );

        assertThat(
                chatModelTool.description()
        ).isEqualTo(
                "현재 시간을 조회합니다."
        );

        assertThat(
                chatModelTool.parameters()
        ).hasSize(
                1
        );

        assertThat(
                chatModelTool.parameters()
        ).containsKey(
                "zoneId"
        );

        ChatModelToolParameter parameter =
                chatModelTool.parameters()
                        .get(
                                "zoneId"
                        );

        assertThat(
                parameter.type()
        ).isEqualTo(
                "string"
        );

        assertThat(
                parameter.description()
        ).isEqualTo(
                "IANA 시간대 ID"
        );

        assertThat(
                parameter.required()
        ).isFalse();
    }
}