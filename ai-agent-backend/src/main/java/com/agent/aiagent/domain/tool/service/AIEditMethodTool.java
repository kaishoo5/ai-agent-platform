package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.codeedit.model.MethodEditRequest;
import com.agent.aiagent.domain.codeedit.model.MethodEditResult;
import com.agent.aiagent.domain.codeedit.service.AIEditMethodService;
import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AIEditMethodTool implements AgentTool {

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "ai_edit_method",
                    "Java 클래스의 특정 메서드를 사용자의 요청에 맞게 AI가 수정합니다. "
                            + "현재 메서드를 조회한 뒤 수정된 전체 메서드를 생성하고 실제 소스 파일에 적용합니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "수정할 Java 클래스 이름입니다. .java 확장자는 생략합니다. 예: CalculatorTool",
                                    true
                            ),
                            "methodName",
                            new ToolParameter(
                                    "string",
                                    "수정할 메서드 이름입니다. 예: execute",
                                    true
                            ),
                            "instruction",
                            new ToolParameter(
                                    "string",
                                    "메서드를 어떻게 수정할지 설명하는 요청입니다. 예: 메서드 시작 부분에 로그를 추가하고 기존 로직은 변경하지 마.",
                                    true
                            ),
                            "path",
                            new ToolParameter(
                                    "string",
                                    "검색을 시작할 작업 폴더 기준 상대 경로입니다. 생략하면 작업 폴더 최상위에서 검색합니다.",
                                    false
                            )
                    )
            );

    private final AIEditMethodService aiEditMethodService;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        String className =
                getArgument(
                        arguments,
                        "className"
                );

        String methodName =
                getArgument(
                        arguments,
                        "methodName"
                );

        String instruction =
                getArgument(
                        arguments,
                        "instruction"
                );

        String path =
                getArgument(
                        arguments,
                        "path"
                );

        if (className == null) {
            return ToolResult.failure(
                    "수정할 클래스 이름이 없습니다."
            );
        }

        if (methodName == null) {
            return ToolResult.failure(
                    "수정할 메서드 이름이 없습니다."
            );
        }

        if (instruction == null) {
            return ToolResult.failure(
                    "메서드 수정 요청 내용이 없습니다."
            );
        }

        try {
            MethodEditRequest request =
                    new MethodEditRequest(
                            className,
                            methodName,
                            instruction,
                            path
                    );

            MethodEditResult result =
                    aiEditMethodService.edit(
                            request
                    );

            if (!result.success()) {
                return ToolResult.failure(
                        result.message()
                );
            }

            log.info(
                    "AI Edit Method Tool 실행 완료. className={}, methodName={}, path={}",
                    className,
                    methodName,
                    path
            );

            return ToolResult.success(
                    result.message()
            );
        } catch (Exception exception) {
            log.error(
                    "AI Edit Method Tool 실행 실패. className={}, methodName={}, path={}",
                    className,
                    methodName,
                    path,
                    exception
            );

            return ToolResult.failure(
                    "AI 메서드 수정 요청을 처리하는 중 오류가 발생했습니다."
            );
        }
    }

    private String getArgument(
            Map<String, Object> arguments,
            String name
    ) {
        if (arguments == null) {
            return null;
        }

        Object value =
                arguments.get(
                        name
                );

        if (value == null) {
            return null;
        }

        String normalizedValue =
                value.toString()
                        .trim();

        return normalizedValue.isBlank()
                ? null
                : normalizedValue;
    }
}