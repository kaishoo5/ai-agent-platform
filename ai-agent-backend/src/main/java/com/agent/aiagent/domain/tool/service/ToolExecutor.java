package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolExecutionRequest;
import com.agent.aiagent.domain.tool.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry toolRegistry;

    public ToolResult execute(
            ToolExecutionRequest request
    ) {
        if (request == null) {
            return ToolResult.failure(
                    "Tool 실행 요청이 없습니다."
            );
        }

        String toolName =
                request.toolName();

        if (
                toolName == null
                        || toolName.isBlank()
        ) {
            return ToolResult.failure(
                    "Tool 이름이 없습니다."
            );
        }

        Optional<AgentTool> optionalTool =
                toolRegistry.find(
                        toolName
                );

        if (optionalTool.isEmpty()) {
            log.warn(
                    "등록되지 않은 Tool 실행 요청입니다. toolName={}",
                    toolName
            );

            return ToolResult.failure(
                    "등록되지 않은 Tool입니다: "
                            + toolName
            );
        }

        Map<String, Object> arguments =
                request.arguments() == null
                        ? Map.of()
                        : request.arguments();

        try {
            log.info(
                    "Tool 실행 시작. toolName={}, arguments={}",
                    toolName,
                    arguments
            );

            ToolResult result =
                    optionalTool
                            .get()
                            .execute(
                                    arguments
                            );

            log.info(
                    "Tool 실행 완료. toolName={}, success={}",
                    toolName,
                    result.success()
            );

            return result;
        } catch (Exception exception) {
            log.error(
                    "Tool 실행 중 오류가 발생했습니다. toolName={}",
                    toolName,
                    exception
            );

            return ToolResult.failure(
                    "Tool 실행 중 오류가 발생했습니다: "
                            + exception.getMessage()
            );
        }
    }
}