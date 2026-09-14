package com.agent.aiagent.domain.codeedit.service;

import com.agent.aiagent.domain.codeedit.model.CodeEditPatch;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.service.AddFieldTool;
import com.agent.aiagent.domain.tool.service.AddImportTool;
import com.agent.aiagent.domain.tool.service.AppendMethodTool;
import com.agent.aiagent.domain.tool.service.ReplaceMethodTool;
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

        if (
                !StringUtils.hasText(
                        patch.code()
                )
        ) {
            return ToolResult.failure(
                    "Patch 코드가 없습니다."
            );
        }

        if (
                TYPE_REPLACE_METHOD.equals(
                        patch.type()
                )
                        && !StringUtils.hasText(
                        patch.methodName()
                )
        ) {
            return ToolResult.failure(
                    "replace_method Patch에는 methodName이 필요합니다."
            );
        }

        return null;
    }
}