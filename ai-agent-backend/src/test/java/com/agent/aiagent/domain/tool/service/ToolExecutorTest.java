package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolExecutionRequest;
import com.agent.aiagent.domain.tool.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {

    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        ToolRegistry toolRegistry =
                new ToolRegistry(
                        List.of(
                                new CurrentTimeTool()
                        )
                );

        toolExecutor =
                new ToolExecutor(
                        toolRegistry
                );
    }

    @Test
    void 현재_시간_Tool을_실행한다() {
        ToolExecutionRequest request =
                new ToolExecutionRequest(
                        "current_time",
                        Map.of(
                                "zoneId",
                                "Asia/Seoul"
                        )
                );

        ToolResult result =
                toolExecutor.execute(
                        request
                );

        assertThat(result.success())
                .isTrue();

        assertThat(result.content())
                .contains("KST");
    }

    @Test
    void 등록되지_않은_Tool은_실패한다() {
        ToolExecutionRequest request =
                new ToolExecutionRequest(
                        "unknown_tool",
                        Map.of()
                );

        ToolResult result =
                toolExecutor.execute(
                        request
                );

        assertThat(result.success())
                .isFalse();

        assertThat(result.content())
                .contains("등록되지 않은 Tool");
    }

    @Test
    void Tool_이름이_없으면_실패한다() {
        ToolExecutionRequest request =
                new ToolExecutionRequest(
                        null,
                        Map.of()
                );

        ToolResult result =
                toolExecutor.execute(
                        request
                );

        assertThat(result.success())
                .isFalse();

        assertThat(result.content())
                .contains("Tool 이름이 없습니다");
    }

    @Test
    void arguments가_null이면_빈_Map으로_실행한다() {
        ToolExecutionRequest request =
                new ToolExecutionRequest(
                        "current_time",
                        null
                );

        ToolResult result =
                toolExecutor.execute(
                        request
                );

        assertThat(result.success())
                .isTrue();

        assertThat(result.content())
                .contains("KST");
    }
}