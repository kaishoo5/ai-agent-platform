package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentTimeToolTest {

    private final CurrentTimeTool currentTimeTool =
            new CurrentTimeTool();

    @Test
    void 서울_현재_시간을_조회한다() {
        ToolResult result =
                currentTimeTool.execute(
                        Map.of(
                                "zoneId",
                                "Asia/Seoul"
                        )
                );

        assertThat(result.success())
                .isTrue();

        assertThat(result.content())
                .contains("KST");
    }

    @Test
    void 시간대가_없으면_서울을_사용한다() {
        ToolResult result =
                currentTimeTool.execute(
                        Map.of()
                );

        assertThat(result.success())
                .isTrue();

        assertThat(result.content())
                .contains("KST");
    }

    @Test
    void 잘못된_시간대를_입력하면_실패한다() {
        ToolResult result =
                currentTimeTool.execute(
                        Map.of(
                                "zoneId",
                                "invalid-zone"
                        )
                );

        assertThat(result.success())
                .isFalse();

        assertThat(result.content())
                .contains("유효하지 않은 시간대");
    }
}