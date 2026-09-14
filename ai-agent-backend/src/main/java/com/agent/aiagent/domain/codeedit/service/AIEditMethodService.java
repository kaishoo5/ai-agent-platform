package com.agent.aiagent.domain.codeedit.service;

import com.agent.aiagent.domain.codeedit.model.MethodEditPatch;
import com.agent.aiagent.domain.codeedit.model.MethodEditRequest;
import com.agent.aiagent.domain.codeedit.model.MethodEditResult;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.service.FindMethodTool;
import com.agent.aiagent.domain.tool.service.ReplaceMethodTool;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIEditMethodService {

    private static final String PATCH_TYPE_REPLACE_METHOD =
            "replace_method";

    private final FindMethodTool findMethodTool;
    private final ReplaceMethodTool replaceMethodTool;
    private final ChatModelProvider chatModelProvider;
    private final MethodEditPromptBuilder methodEditPromptBuilder;
    private final ObjectMapper objectMapper;

    public MethodEditResult edit(
            MethodEditRequest request
    ) {
        MethodEditResult validationResult =
                validateRequest(
                        request
                );

        if (validationResult != null) {
            return validationResult;
        }

        log.info(
                "AI 메서드 수정 시작. className={}, methodName={}, path={}",
                request.className(),
                request.methodName(),
                request.path()
        );

        ToolResult findMethodResult =
                findMethod(
                        request
                );

        if (!findMethodResult.success()) {
            log.warn(
                    "AI 메서드 수정 대상 조회 실패. className={}, methodName={}, message={}",
                    request.className(),
                    request.methodName(),
                    findMethodResult.content()
            );

            return MethodEditResult.fail(
                    findMethodResult.content()
            );
        }

        String prompt =
                methodEditPromptBuilder.build(
                        request,
                        findMethodResult.content()
                );

        MethodEditPatch patch;

        try {
            patch =
                    createPatch(
                            prompt
                    );
        } catch (Exception exception) {
            log.error(
                    "AI 메서드 수정 Patch 생성 실패. className={}, methodName={}",
                    request.className(),
                    request.methodName(),
                    exception
            );

            return MethodEditResult.fail(
                    "AI가 메서드 수정 Patch를 생성하는 중 오류가 발생했습니다: "
                            + exception.getMessage()
            );
        }

        MethodEditResult patchValidationResult =
                validatePatch(
                        request,
                        patch
                );

        if (patchValidationResult != null) {
            return patchValidationResult;
        }

        ToolResult replaceMethodResult =
                replaceMethod(
                        request,
                        patch
                );

        if (!replaceMethodResult.success()) {
            log.warn(
                    "AI 메서드 수정 적용 실패. className={}, methodName={}, message={}",
                    request.className(),
                    request.methodName(),
                    replaceMethodResult.content()
            );

            return MethodEditResult.fail(
                    replaceMethodResult.content()
            );
        }

        log.info(
                "AI 메서드 수정 완료. className={}, methodName={}",
                request.className(),
                request.methodName()
        );

        return MethodEditResult.success(
                replaceMethodResult.content(),
                patch
        );
    }

    private ToolResult findMethod(
            MethodEditRequest request
    ) {
        Map<String, Object> arguments =
                new LinkedHashMap<>();

        arguments.put(
                "className",
                request.className()
        );

        arguments.put(
                "methodName",
                request.methodName()
        );

        if (StringUtils.hasText(request.path())) {
            arguments.put(
                    "path",
                    request.path()
            );
        }

        return findMethodTool.execute(
                arguments
        );
    }

    private MethodEditPatch createPatch(
            String prompt
    ) throws JacksonException {
        ChatModelResponse response =
                chatModelProvider.chatOnce(
                        new ChatModelRequest(
                                ChatModelType.TEXT,
                                List.of(
                                        new ChatModelMessage(
                                                "user",
                                                prompt,
                                                null
                                        )
                                ),
                                List.of()
                        )
                );

        if (
                response == null
                        || !StringUtils.hasText(
                        response.content()
                )
        ) {
            throw new IllegalStateException(
                    "AI 응답이 비어 있습니다."
            );
        }

        String json =
                normalizeJsonResponse(
                        response.content()
                );

        log.debug(
                "AI 메서드 수정 Patch 응답={}",
                json
        );

        return objectMapper.readValue(
                json,
                MethodEditPatch.class
        );
    }

    private ToolResult replaceMethod(
            MethodEditRequest request,
            MethodEditPatch patch
    ) {
        Map<String, Object> arguments =
                new LinkedHashMap<>();

        arguments.put(
                "className",
                patch.className()
        );

        arguments.put(
                "methodName",
                patch.methodName()
        );

        arguments.put(
                "newMethodCode",
                patch.code()
        );

        arguments.put(
                "createBackup",
                true
        );

        if (StringUtils.hasText(request.path())) {
            arguments.put(
                    "path",
                    request.path()
            );
        }

        return replaceMethodTool.execute(
                arguments
        );
    }

    private MethodEditResult validateRequest(
            MethodEditRequest request
    ) {
        if (request == null) {
            return MethodEditResult.fail(
                    "메서드 수정 요청이 없습니다."
            );
        }

        if (!StringUtils.hasText(request.className())) {
            return MethodEditResult.fail(
                    "수정할 클래스 이름이 없습니다."
            );
        }

        if (!StringUtils.hasText(request.methodName())) {
            return MethodEditResult.fail(
                    "수정할 메서드 이름이 없습니다."
            );
        }

        if (!StringUtils.hasText(request.instruction())) {
            return MethodEditResult.fail(
                    "메서드 수정 요청 내용이 없습니다."
            );
        }

        return null;
    }

    private MethodEditResult validatePatch(
            MethodEditRequest request,
            MethodEditPatch patch
    ) {
        if (patch == null) {
            return MethodEditResult.fail(
                    "AI가 생성한 수정 Patch가 없습니다."
            );
        }

        if (
                !PATCH_TYPE_REPLACE_METHOD.equals(
                        patch.type()
                )
        ) {
            return MethodEditResult.fail(
                    "지원하지 않는 Patch 타입입니다: "
                            + patch.type()
            );
        }

        if (
                !request.className().equals(
                        patch.className()
                )
        ) {
            return MethodEditResult.fail(
                    "AI가 대상 클래스 이름을 변경했습니다."
            );
        }

        if (
                !request.methodName().equals(
                        patch.methodName()
                )
        ) {
            return MethodEditResult.fail(
                    "AI가 대상 메서드 이름을 변경했습니다."
            );
        }

        if (!StringUtils.hasText(patch.code())) {
            return MethodEditResult.fail(
                    "AI가 수정된 메서드 코드를 생성하지 않았습니다."
            );
        }

        if (patch.code().contains("```")) {
            return MethodEditResult.fail(
                    "AI가 메서드 코드에 Markdown 코드 블록을 포함했습니다."
            );
        }

        return null;
    }

    private String normalizeJsonResponse(
            String response
    ) {
        String normalized =
                response.trim();

        if (
                normalized.startsWith("```json")
                        && normalized.endsWith("```")
        ) {
            normalized =
                    normalized.substring(
                            "```json".length(),
                            normalized.length() - 3
                    ).trim();
        } else if (
                normalized.startsWith("```")
                        && normalized.endsWith("```")
        ) {
            normalized =
                    normalized.substring(
                            3,
                            normalized.length() - 3
                    ).trim();
        }

        return normalized;
    }
}