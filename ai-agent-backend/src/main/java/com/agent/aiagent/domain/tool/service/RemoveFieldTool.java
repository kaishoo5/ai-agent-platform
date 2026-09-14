package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import com.agent.aiagent.infra.filesystem.FileSystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class RemoveFieldTool implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES =
            1_048_576L;

    private static final int MAX_CLASS_SEARCH_RESULTS =
            50;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "remove_field",
                    "Java 클래스에서 기존 필드를 삭제합니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "필드를 삭제할 Java 클래스 이름입니다.",
                                    true
                            ),
                            "fieldName",
                            new ToolParameter(
                                    "string",
                                    "삭제할 필드 이름입니다.",
                                    true
                            ),
                            "path",
                            new ToolParameter(
                                    "string",
                                    "검색을 시작할 작업 폴더 기준 상대 경로입니다.",
                                    false
                            ),
                            "createBackup",
                            new ToolParameter(
                                    "boolean",
                                    "수정 전 .bak 백업 파일 생성 여부입니다. 기본값은 true입니다.",
                                    false
                            )
                    )
            );

    private final FileSystemProperties fileSystemProperties;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        String className =
                getStringArgument(
                        arguments,
                        "className"
                );

        String fieldName =
                getStringArgument(
                        arguments,
                        "fieldName"
                );

        String path =
                getStringArgument(
                        arguments,
                        "path"
                );

        boolean createBackup =
                getBooleanArgument(
                        arguments,
                        "createBackup",
                        true
                );

        if (className == null) {
            return ToolResult.failure(
                    "className이 없습니다."
            );
        }

        if (fieldName == null) {
            return ToolResult.failure(
                    "fieldName이 없습니다."
            );
        }

        try {
            Path workspaceRoot =
                    getWorkspaceRoot();

            Path searchRoot =
                    resolveSearchRoot(
                            workspaceRoot,
                            path
                    );

            List<Path> matchedFiles =
                    findClassFiles(
                            searchRoot,
                            className
                    );

            if (matchedFiles.isEmpty()) {
                return ToolResult.failure(
                        "Java 클래스를 찾을 수 없습니다: "
                                + className
                );
            }

            if (matchedFiles.size() > 1) {
                return ToolResult.failure(
                        buildMultipleClassMessage(
                                workspaceRoot,
                                className,
                                matchedFiles
                        )
                );
            }

            Path sourceFile =
                    matchedFiles.getFirst();

            validateFile(
                    sourceFile
            );

            String source =
                    Files.readString(
                            sourceFile,
                            StandardCharsets.UTF_8
                    );

            FieldRange fieldRange =
                    findFieldRange(
                            source,
                            fieldName
                    );

            if (fieldRange == null) {
                return ToolResult.failure(
                        """
                        Java 클래스는 찾았지만 필드를 찾지 못했습니다.

                        클래스 이름: %s
                        필드 이름: %s
                        파일 경로: %s
                        """.formatted(
                                className,
                                fieldName,
                                workspaceRoot.relativize(
                                        sourceFile
                                )
                        )
                );
            }

            if (createBackup) {
                createBackup(
                        sourceFile
                );
            }

            String updatedSource =
                    source.substring(
                            0,
                            fieldRange.start()
                    )
                            + source.substring(
                            fieldRange.end()
                    );

            Files.writeString(
                    sourceFile,
                    updatedSource,
                    StandardCharsets.UTF_8
            );

            log.info(
                    "Remove Field Tool 실행 완료. className={}, fieldName={}, path={}, backupCreated={}",
                    className,
                    fieldName,
                    workspaceRoot.relativize(
                            sourceFile
                    ),
                    createBackup
            );

            return ToolResult.success(
                    """
                    필드를 삭제했습니다.

                    클래스 이름: %s
                    필드 이름: %s
                    파일 경로: %s
                    """.formatted(
                            className,
                            fieldName,
                            workspaceRoot.relativize(
                                    sourceFile
                            )
                    )
            );

        } catch (Exception exception) {
            log.error(
                    "Remove Field Tool 실행 실패. className={}, fieldName={}, path={}",
                    className,
                    fieldName,
                    path,
                    exception
            );

            return ToolResult.failure(
                    "필드 삭제 중 오류가 발생했습니다: "
                            + exception.getMessage()
            );
        }
    }

    private FieldRange findFieldRange(
            String source,
            String fieldName
    ) {
        int classBodyStart =
                source.indexOf(
                        "{"
                );

        if (classBodyStart < 0) {
            return null;
        }

        int braceDepth = 1;

        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;

        int statementStart =
                classBodyStart + 1;

        for (
                int index = classBodyStart + 1;
                index < source.length();
                index++
        ) {
            char current =
                    source.charAt(
                            index
                    );

            char next =
                    index + 1 < source.length()
                            ? source.charAt(index + 1)
                            : '\0';

            if (inLineComment) {
                if (current == '\n') {
                    inLineComment = false;

                    if (braceDepth == 1) {
                        statementStart =
                                index + 1;
                    }
                }

                continue;
            }

            if (inBlockComment) {
                if (
                        current == '*'
                                && next == '/'
                ) {
                    inBlockComment = false;
                    index++;
                }

                continue;
            }

            if (inString) {
                if (
                        current == '"'
                                && !escaped
                ) {
                    inString = false;
                }

                escaped =
                        current == '\\'
                                && !escaped;

                if (current != '\\') {
                    escaped = false;
                }

                continue;
            }

            if (inChar) {
                if (
                        current == '\''
                                && !escaped
                ) {
                    inChar = false;
                }

                escaped =
                        current == '\\'
                                && !escaped;

                if (current != '\\') {
                    escaped = false;
                }

                continue;
            }

            if (
                    current == '/'
                            && next == '/'
            ) {
                inLineComment = true;
                index++;
                continue;
            }

            if (
                    current == '/'
                            && next == '*'
            ) {
                inBlockComment = true;
                index++;
                continue;
            }

            if (current == '"') {
                inString = true;
                escaped = false;
                continue;
            }

            if (current == '\'') {
                inChar = true;
                escaped = false;
                continue;
            }

            if (current == '{') {
                braceDepth++;
                continue;
            }

            if (current == '}') {
                braceDepth--;

                if (braceDepth == 0) {
                    break;
                }

                continue;
            }

            if (
                    braceDepth == 1
                            && current == ';'
            ) {
                int statementEnd =
                        index + 1;

                String statement =
                        source.substring(
                                statementStart,
                                statementEnd
                        );

                if (
                        containsFieldName(
                                statement,
                                fieldName
                        )
                ) {
                    int removeStart =
                            findLineStart(
                                    source,
                                    statementStart
                            );

                    int removeEnd =
                            findLineEnd(
                                    source,
                                    statementEnd
                            );

                    return new FieldRange(
                            removeStart,
                            removeEnd
                    );
                }

                statementStart =
                        statementEnd;
            }

            if (
                    braceDepth == 1
                            && current == '\n'
                            && source.substring(
                            statementStart,
                            index
                    ).trim().isEmpty()
            ) {
                statementStart =
                        index + 1;
            }
        }

        return null;
    }

    private boolean containsFieldName(
            String statement,
            String fieldName
    ) {
        String normalized =
                statement.trim();

        if (
                normalized.isEmpty()
                        || normalized.contains("(")
        ) {
            return false;
        }

        return normalized.matches(
                "(?s).*\\b"
                        + java.util.regex.Pattern.quote(
                        fieldName
                )
                        + "\\b.*;"
        );
    }

    private int findLineStart(
            String source,
            int position
    ) {
        int lineStart =
                source.lastIndexOf(
                        '\n',
                        Math.max(
                                0,
                                position - 1
                        )
                );

        return lineStart < 0
                ? 0
                : lineStart + 1;
    }

    private int findLineEnd(
            String source,
            int position
    ) {
        int lineEnd =
                source.indexOf(
                        '\n',
                        position
                );

        if (lineEnd < 0) {
            return source.length();
        }

        return lineEnd + 1;
    }

    private Path getWorkspaceRoot() {
        Path workspaceRoot =
                Path.of(
                                fileSystemProperties.getRootDirectory()
                        )
                        .toAbsolutePath()
                        .normalize();

        if (!Files.exists(workspaceRoot)) {
            throw new IllegalStateException(
                    "Workspace Root가 존재하지 않습니다: "
                            + workspaceRoot
            );
        }

        return workspaceRoot;
    }

    private Path resolveSearchRoot(
            Path workspaceRoot,
            String path
    ) {
        if (path == null) {
            return workspaceRoot;
        }

        Path searchRoot =
                workspaceRoot.resolve(
                                path
                        )
                        .normalize();

        if (!searchRoot.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException(
                    "Workspace 외부 경로에는 접근할 수 없습니다."
            );
        }

        if (!Files.exists(searchRoot)) {
            throw new IllegalArgumentException(
                    "검색 경로가 존재하지 않습니다: "
                            + path
            );
        }

        return searchRoot;
    }

    private List<Path> findClassFiles(
            Path searchRoot,
            String className
    ) throws IOException {
        List<Path> matchedFiles =
                new ArrayList<>();

        String targetFileName =
                className + ".java";

        try (
                Stream<Path> paths =
                        Files.walk(
                                searchRoot
                        )
        ) {
            paths.filter(
                            Files::isRegularFile
                    )
                    .filter(
                            path ->
                                    !isIgnoredPath(
                                            path
                                    )
                    )
                    .filter(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .equals(
                                                    targetFileName
                                            )
                    )
                    .limit(
                            MAX_CLASS_SEARCH_RESULTS
                    )
                    .forEach(
                            matchedFiles::add
                    );
        }

        return matchedFiles;
    }

    private boolean isIgnoredPath(
            Path path
    ) {
        for (Path part : path) {
            String value =
                    part.toString();

            if (
                    ".git".equalsIgnoreCase(value)
                            || ".idea".equalsIgnoreCase(value)
                            || "build".equalsIgnoreCase(value)
                            || "target".equalsIgnoreCase(value)
                            || "node_modules".equalsIgnoreCase(value)
                            || ".ai-agent-transactions".equalsIgnoreCase(value)
            ) {
                return true;
            }
        }

        return false;
    }

    private void validateFile(
            Path sourceFile
    ) throws IOException {
        long fileSize =
                Files.size(
                        sourceFile
                );

        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "파일 크기가 너무 큽니다: "
                            + fileSize
            );
        }
    }

    private void createBackup(
            Path sourceFile
    ) throws IOException {
        Path backupFile =
                sourceFile.resolveSibling(
                        sourceFile.getFileName()
                                + ".bak"
                );

        Files.copy(
                sourceFile,
                backupFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
        );
    }

    private String buildMultipleClassMessage(
            Path workspaceRoot,
            String className,
            List<Path> matchedFiles
    ) {
        StringBuilder builder =
                new StringBuilder();

        builder.append(
                "동일한 클래스 이름의 Java 파일이 여러 개 발견되었습니다.\n"
        );

        builder.append(
                "클래스 이름: "
        );

        builder.append(
                className
        );

        for (Path matchedFile : matchedFiles) {
            builder.append(
                    "\n- "
            );

            builder.append(
                    workspaceRoot.relativize(
                            matchedFile
                    )
            );
        }

        return builder.toString();
    }

    private String getStringArgument(
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

    private boolean getBooleanArgument(
            Map<String, Object> arguments,
            String name,
            boolean defaultValue
    ) {
        if (
                arguments == null
                        || arguments.get(name) == null
        ) {
            return defaultValue;
        }

        Object value =
                arguments.get(
                        name
                );

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        return Boolean.parseBoolean(
                value.toString()
        );
    }

    private record FieldRange(
            int start,
            int end
    ) {
    }
}