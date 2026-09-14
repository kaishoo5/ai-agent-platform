package com.agent.aiagent.domain.codeedit.service;

import com.agent.aiagent.domain.codeedit.model.CodeEditPatch;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PatchExecutor {

    private static final String TYPE_ADD_IMPORT =
            "add_import";

    private static final String TYPE_ADD_FIELD =
            "add_field";

    private static final String TYPE_APPEND_METHOD =
            "append_method";

    private static final String TYPE_REPLACE_METHOD =
            "replace_method";

    private final AddImportTool addImportTool;
    private final AddFieldTool addFieldTool;
    private final AppendMethodTool appendMethodTool;
    private final ReplaceMethodTool replaceMethodTool;
    private final RemoveFieldTool removeFieldTool;
    private final RemoveMethodTool removeMethodTool;

    public ToolResult execute(
            CodeEditPatch patch
    ) {
        ToolResult validationResult =
                validate(
                        patch
                );

        if (validationResult != null) {
            return validationResult;
        }

        log.info(
                "Code Edit Patch 실행 시작. type={}, className={}, methodName={}, path={}",
                patch.type(),
                patch.className(),
                patch.methodName(),
                patch.path()
        );

        ToolResult result =
                switch (
                        patch.type()
                        ) {
                    case TYPE_ADD_IMPORT ->
                            executeAddImport(
                                    patch
                            );

                    case TYPE_ADD_FIELD ->
                            executeAddField(
                                    patch
                            );

                    case TYPE_APPEND_METHOD ->
                            executeAppendMethod(
                                    patch
                            );

                    case TYPE_REPLACE_METHOD ->
                            executeReplaceMethod(
                                    patch
                            );

                    case "remove_field" ->
                            executeRemoveField(
                                    patch
                            );

                    case "remove_method" ->
                            executeRemoveMethod(
                                    patch
                            );

                    default ->
                            ToolResult.failure(
                                    "지원하지 않는 Patch 타입입니다: "
                                            + patch.type()
                            );
                };

        if (result.success()) {
            log.info(
                    "Code Edit Patch 실행 완료. type={}, className={}, methodName={}",
                    patch.type(),
                    patch.className(),
                    patch.methodName()
            );
        } else {
            log.warn(
                    "Code Edit Patch 실행 실패. type={}, className={}, methodName={}, message={}",
                    patch.type(),
                    patch.className(),
                    patch.methodName(),
                    result.content()
            );
        }

        return result;
    }

    private ToolResult executeAddImport(
            CodeEditPatch patch
    ) {
        Map<String, Object> arguments =
                createBaseArguments(
                        patch
                );

        arguments.put(
                "importName",
                patch.code()
        );

        arguments.put(
                "createBackup",
                false
        );

        return addImportTool.execute(
                arguments
        );
    }

    private ToolResult executeAddField(
            CodeEditPatch patch
    ) {
        Map<String, Object> arguments =
                createBaseArguments(
                        patch
                );

        arguments.put(
                "fieldCode",
                patch.code()
        );

        arguments.put(
                "createBackup",
                false
        );

        return addFieldTool.execute(
                arguments
        );
    }

    private ToolResult executeAppendMethod(
            CodeEditPatch patch
    ) {
        Map<String, Object> arguments =
                createBaseArguments(
                        patch
                );

        arguments.put(
                "methodCode",
                patch.code()
        );

        arguments.put(
                "createBackup",
                false
        );

        return appendMethodTool.execute(
                arguments
        );
    }

    private ToolResult executeReplaceMethod(
            CodeEditPatch patch
    ) {
        Map<String, Object> arguments =
                createBaseArguments(
                        patch
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
                false
        );

        return replaceMethodTool.execute(
                arguments
        );
    }

    private ToolResult executeRemoveField(
            CodeEditPatch patch
    ) {
        return removeFieldTool.execute(
                Map.of(
                        "className",
                        patch.className(),
                        "fieldName",
                        patch.fieldName(),
                        "path",
                        patch.path() == null
                                ? ""
                                : patch.path(),
                        "createBackup",
                        false
                )
        );
    }

    private ToolResult executeRemoveMethod(
            CodeEditPatch patch
    ) {
        return removeMethodTool.execute(
                Map.of(
                        "className",
                        patch.className(),
                        "methodName",
                        patch.methodName(),
                        "path",
                        patch.path() == null
                                ? ""
                                : patch.path(),
                        "createBackup",
                        false
                )
        );
    }

    private Map<String, Object> createBaseArguments(
            CodeEditPatch patch
    ) {
        Map<String, Object> arguments =
                new LinkedHashMap<>();

        arguments.put(
                "className",
                patch.className()
        );

        if (
                StringUtils.hasText(
                        patch.path()
                )
        ) {
            arguments.put(
                    "path",
                    patch.path()
            );
        }

        return arguments;
    }

    private ToolResult validate(
            CodeEditPatch patch
    ) {
        if (patch == null) {
            return ToolResult.failure(
                    "실행할 Patch가 없습니다."
            );
        }

        if (
                !StringUtils.hasText(
                        patch.type()
                )
        ) {
            return ToolResult.failure(
                    "Patch 타입이 없습니다."
            );
        }

        if (
                !StringUtils.hasText(
                        patch.className()
                )
        ) {
            return ToolResult.failure(
                    "Patch 대상 클래스 이름이 없습니다."
            );
        }

        switch (patch.type()) {
            case TYPE_ADD_IMPORT,
                 TYPE_ADD_FIELD,
                 TYPE_APPEND_METHOD -> {

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

            case TYPE_REPLACE_METHOD -> {

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
                        "지원하지 않는 Patch 타입입니다: "
                                + patch.type()
                );
            }
        }

        return null;
    }
}