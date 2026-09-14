package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.codeedit.model.CodeEditRequest;
import com.agent.aiagent.domain.codeedit.model.CodeEditResult;
import com.agent.aiagent.domain.codeedit.service.AIEditEngine;
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
public class AIEditCodeTool implements AgentTool {

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "ai_edit_code",
                    "사용자의 자연어 요청을 분석해서 Java 소스 코드를 수정합니다. "
                            + "import 추가, 필드 추가, 메서드 추가, 기존 메서드 수정 등 "
                            + "여러 코드 변경이 필요한 요청을 한 번에 처리할 수 있습니다.",
                    Map.of(
                            "instruction",
                            new ToolParameter(
                                    "string",
                                    "Java 코드를 어떻게 수정할지 설명하는 자연어 요청입니다.",
                                    true
                            ),
                            "path",
                            new ToolParameter(
                                    "string",
                                    "코드 수정 대상 검색을 시작할 작업 폴더 기준 상대 경로입니다. 생략하면 작업 폴더 최상위에서 처리합니다.",
                                    false
                            )
                    )
            );

    private final AIEditEngine aiEditEngine;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
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

        if (instruction == null) {
            return ToolResult.failure(
                    "코드 수정 요청 내용이 없습니다."
            );
        }

        try {
            CodeEditRequest request =
                    new CodeEditRequest(
                            instruction,
                            path
                    );

            CodeEditResult result =
                    aiEditEngine.edit(
                            request
                    );

            if (!result.success()) {
                return ToolResult.failure(
                        result.message()
                );
            }

            log.info(
                    "AI Edit Code Tool 실행 완료. patchCount={}, path={}",
                    result.patches().size(),
                    path
            );

            return ToolResult.success(
                    buildSuccessMessage(
                            result
                    )
            );
        } catch (Exception exception) {
            log.error(
                    "AI Edit Code Tool 실행 실패. instruction={}, path={}",
                    instruction,
                    path,
                    exception
            );

            return ToolResult.failure(
                    "AI 코드 수정 요청을 처리하는 중 오류가 발생했습니다."
            );
        }
    }

    private String buildSuccessMessage(
            CodeEditResult result
    ) {
        StringBuilder builder =
                new StringBuilder();

        builder.append(
                result.message()
        );

        if (
                result.patches() != null
                        && !result.patches().isEmpty()
        ) {
            builder.append(
                    "\n실행된 Patch:"
            );

            for (
                    int index = 0;
                    index < result.patches().size();
                    index++
            ) {
                var patch =
                        result.patches().get(
                                index
                        );

                builder.append(
                        "\n"
                );

                builder.append(
                        index + 1
                );

                builder.append(
                        ". "
                );

                builder.append(
                        patch.type()
                );

                builder.append(
                        " - "
                );

                builder.append(
                        patch.className()
                );

                if (
                        patch.methodName() != null
                                && !patch.methodName().isBlank()
                ) {
                    builder.append(
                            "."
                    );

                    builder.append(
                            patch.methodName()
                    );
                }
            }
        }

        return builder.toString();
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