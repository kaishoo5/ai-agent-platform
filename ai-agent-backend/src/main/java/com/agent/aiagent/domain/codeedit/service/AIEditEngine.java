package com.agent.aiagent.domain.codeedit.service;

import com.agent.aiagent.domain.codeedit.model.CodeBuildValidationResult;
import com.agent.aiagent.domain.codeedit.model.CodeEditPatch;
import com.agent.aiagent.domain.codeedit.model.CodeEditRequest;
import com.agent.aiagent.domain.codeedit.model.CodeEditResult;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIEditEngine {

    private final ChatModelProvider chatModelProvider;
    private final CodeEditPromptBuilder codeEditPromptBuilder;
    private final PatchExecutor patchExecutor;
    private final CodeEditTransactionManager codeEditTransactionManager;
    private final ObjectMapper objectMapper;
    private final CodeBuildValidator codeBuildValidator;

    public CodeEditResult edit(
            CodeEditRequest request
    ) {
        CodeEditResult validationResult =
                validateRequest(
                        request
                );

        if (validationResult != null) {
            return validationResult;
        }

        log.info(
                "AI Code Edit 시작. instruction={}, path={}",
                request.instruction(),
                request.path()
        );

        List<CodeEditPatch> patches;

        try {
            patches =
                    createPatches(
                            request
                    );
        } catch (Exception exception) {
            log.error(
                    "AI Code Edit Patch 생성 실패.",
                    exception
            );

            return CodeEditResult.fail(
                    "AI가 코드 수정 Patch를 생성하는 중 오류가 발생했습니다: "
                            + exception.getMessage()
            );
        }

        if (patches.isEmpty()) {
            return CodeEditResult.fail(
                    "AI가 실행할 코드 수정 Patch를 생성하지 않았습니다."
            );
        }

        for (CodeEditPatch patch : patches) {
            ToolResult patchValidationResult =
                    validatePatch(
                            patch
                    );

            if (patchValidationResult != null) {
                return CodeEditResult.fail(
                        patchValidationResult.content()
                );
            }
        }

        CodeEditTransactionManager.CodeEditTransaction transaction;

        try {
            transaction =
                    codeEditTransactionManager.begin(
                            patches
                    );
        } catch (Exception exception) {
            log.error(
                    "AI Code Edit Transaction 시작 실패.",
                    exception
            );

            return CodeEditResult.fail(
                    "코드 수정 Transaction을 시작하지 못했습니다: "
                            + exception.getMessage()
            );
        }

        List<CodeEditPatch> executedPatches =
                new ArrayList<>();

        try {
            for (CodeEditPatch patch : patches) {
                ToolResult result =
                        patchExecutor.execute(
                                patch
                        );

                if (!result.success()) {
                    throw new PatchExecutionException(
                            patch,
                            result.content()
                    );
                }

                executedPatches.add(
                        patch
                );
            }

            CodeBuildValidationResult buildResult =
                    codeBuildValidator.validate();

            if (!buildResult.success()) {
                throw new IllegalStateException(
                        "Code Build 검증 실패.\n"
                                + buildResult.message()
                );
            }

            codeEditTransactionManager.commit(
                    transaction
            );

            log.info(
                    "AI Code Edit 완료. patchCount={}",
                    executedPatches.size()
            );

            return CodeEditResult.success(
                    "AI 코드 수정이 완료되었습니다. 실행된 Patch 수: "
                            + executedPatches.size(),
                    executedPatches
            );
        } catch (Exception exception) {
            log.error(
                    "AI Code Edit 실행 실패. Rollback 시작. executedPatchCount={}",
                    executedPatches.size(),
                    exception
            );

            try {
                codeEditTransactionManager.rollback(
                        transaction
                );
            } catch (Exception rollbackException) {
                log.error(
                        "AI Code Edit Rollback 실패.",
                        rollbackException
                );

                return CodeEditResult.fail(
                        "코드 수정 중 오류가 발생했고 Rollback도 완전히 수행되지 못했습니다. "
                                + "원본 파일을 확인해주세요. "
                                + "수정 오류: "
                                + exception.getMessage()
                                + " / Rollback 오류: "
                                + rollbackException.getMessage()
                );
            }

            return CodeEditResult.fail(
                    "코드 수정 중 오류가 발생하여 모든 변경을 Rollback했습니다: "
                            + exception.getMessage()
            );
        }
    }

    private List<CodeEditPatch> createPatches(
            CodeEditRequest request
    ) throws JacksonException {
        String prompt =
                codeEditPromptBuilder.build(
                        request
                );

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
                "AI Code Edit Patch 응답={}",
                json
        );

        return objectMapper.readValue(
                json,
                new TypeReference<List<CodeEditPatch>>() {
                }
        );
    }

    private CodeEditResult validateRequest(
            CodeEditRequest request
    ) {
        if (request == null) {
            return CodeEditResult.fail(
                    "코드 수정 요청이 없습니다."
            );
        }

        if (
                !StringUtils.hasText(
                        request.instruction()
                )
        ) {
            return CodeEditResult.fail(
                    "코드 수정 요청 내용이 없습니다."
            );
        }

        return null;
    }

    private ToolResult validatePatch(
            CodeEditPatch patch
    ) {
        if (patch == null) {
            return ToolResult.failure(
                    "AI가 생성한 Patch가 null입니다."
            );
        }

        if (
                !StringUtils.hasText(
                        patch.type()
                )
        ) {
            return ToolResult.failure(
                    "Patch type이 없습니다."
            );
        }

        if (
                !StringUtils.hasText(
                        patch.className()
                )
        ) {
            return ToolResult.failure(
                    "Patch className이 없습니다."
            );
        }

        switch (patch.type()) {
            case "add_import",
                 "add_field",
                 "append_method" -> {

                if (
                        !StringUtils.hasText(
                                patch.code()
                        )
                ) {
                    return ToolResult.failure(
                            patch.type()
                                    + " Patch에는 code가 필요합니다."
                    );
                }
            }

            case "replace_method" -> {

                if (
                        !StringUtils.hasText(
                                patch.methodName()
                        )
                ) {
                    return ToolResult.failure(
                            "replace_method Patch에는 methodName이 필요합니다."
                    );
                }

                if (
                        !StringUtils.hasText(
                                patch.code()
                        )
                ) {
                    return ToolResult.failure(
                            "replace_method Patch에는 code가 필요합니다."
                    );
                }
            }

            case "remove_field" -> {

                if (
                        !StringUtils.hasText(
                                patch.fieldName()
                        )
                ) {
                    return ToolResult.failure(
                            "remove_field Patch에는 fieldName이 필요합니다."
                    );
                }
            }

            case "remove_method" -> {

                if (
                        !StringUtils.hasText(
                                patch.methodName()
                        )
                ) {
                    return ToolResult.failure(
                            "remove_method Patch에는 methodName이 필요합니다."
                    );
                }
            }

            default -> {
                return ToolResult.failure(
                        "지원하지 않는 Patch type입니다: "
                                + patch.type()
                );
            }
        }

        if (
                StringUtils.hasText(
                        patch.code()
                )
                        && patch.code().contains(
                        "```"
                )
        ) {
            return ToolResult.failure(
                    "Patch code에 Markdown 코드 블록이 포함되어 있습니다."
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
                normalized.startsWith(
                        "```json"
                )
                        && normalized.endsWith(
                        "```"
                )
        ) {
            normalized =
                    normalized.substring(
                            "```json".length(),
                            normalized.length() - 3
                    ).trim();
        } else if (
                normalized.startsWith(
                        "```"
                )
                        && normalized.endsWith(
                        "```"
                )
        ) {
            normalized =
                    normalized.substring(
                            3,
                            normalized.length() - 3
                    ).trim();
        }

        return normalized;
    }

    private static class PatchExecutionException
            extends RuntimeException {

        private PatchExecutionException(
                CodeEditPatch patch,
                String message
        ) {
            super(
                    "Patch 실행 실패. type="
                            + patch.type()
                            + ", className="
                            + patch.className()
                            + ", message="
                            + message
            );
        }
    }
}