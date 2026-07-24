package com.agent.aiagent.domain.agent.service;

import com.agent.aiagent.domain.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolManager {

    /*
     * AgentTool을 구현하고 Spring Bean으로 등록된 Tool들이
     * 자동으로 이 List에 주입된다.
     */
    private final List<AgentTool> tools;

    /**
     * 사용자의 질문을 처리할 수 있는 Tool을 찾는다.
     */
    public Optional<AgentTool> findSupportedTool(
            String question
    ) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

        return tools.stream()
                .filter(tool -> {
                    try {
                        return tool.supports(
                                question
                        );
                    } catch (Exception exception) {
                        log.warn(
                                "Tool 지원 여부 확인 중 오류가 발생했습니다. toolName={}, question={}",
                                tool.getName(),
                                question,
                                exception
                        );

                        return false;
                    }
                })
                .findFirst();
    }

    /**
     * 사용자의 질문을 처리할 수 있는 Tool이 있으면 실행한다.
     */
    public Optional<ToolExecutionResult> executeSupportedTool(
            String question
    ) {
        Optional<AgentTool> optionalTool =
                findSupportedTool(
                        question
                );

        if (optionalTool.isEmpty()) {
            log.debug(
                    "사용 가능한 Tool이 없습니다. question={}",
                    question
            );

            return Optional.empty();
        }

        AgentTool tool =
                optionalTool.get();

        log.info(
                "Tool 실행 시작. toolName={}, question={}",
                tool.getName(),
                question
        );

        try {
            String result =
                    tool.execute(
                            question
                    );

            log.info(
                    "Tool 실행 완료. toolName={}, resultLength={}",
                    tool.getName(),
                    result == null ? 0 : result.length()
            );

            return Optional.of(
                    new ToolExecutionResult(
                            tool.getName(),
                            tool.getDescription(),
                            result
                    )
            );
        } catch (Exception exception) {
            log.error(
                    "Tool 실행 중 오류가 발생했습니다. toolName={}, question={}",
                    tool.getName(),
                    question,
                    exception
            );

            throw exception;
        }
    }

    /**
     * 현재 등록된 모든 Tool 정보를 반환한다.
     *
     * 이후 Ollama에게 Tool 목록을 알려줄 때 사용한다.
     */
    public List<ToolDefinition> getToolDefinitions() {
        return tools.stream()
                .map(tool ->
                        new ToolDefinition(
                                tool.getName(),
                                tool.getDescription()
                        )
                )
                .toList();
    }

    public record ToolExecutionResult(
            String toolName,
            String toolDescription,
            String result
    ) {
    }

    public record ToolDefinition(
            String name,
            String description
    ) {
    }
}