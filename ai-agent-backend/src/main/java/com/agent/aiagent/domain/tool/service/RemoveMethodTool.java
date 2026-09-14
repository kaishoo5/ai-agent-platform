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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class RemoveMethodTool implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES =
            1_048_576L;

    private static final int MAX_CLASS_SEARCH_RESULTS =
            50;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "remove_method",
                    "Java 클래스에서 기존 메서드를 삭제합니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "메서드를 삭제할 Java 클래스 이름입니다.",
                                    true
                            ),
                            "methodName",
                            new ToolParameter(
                                    "string",
                                    "삭제할 메서드 이름입니다.",
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

        String methodName =
                getStringArgument(
                        arguments,
                        "methodName"
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

        if (methodName == null) {
            return ToolResult.failure(
                    "methodName이 없습니다."
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

            List<MethodRange> methodRanges =
                    findMethodRanges(
                            source,
                            methodName
                    );

            if (methodRanges.isEmpty()) {
                return ToolResult.failure(
                        """
                        Java 클래스는 찾았지만 메서드를 찾지 못했습니다.

                        클래스 이름: %s
                        메서드 이름: %s
                        파일 경로: %s
                        """.formatted(
                                className,
                                methodName,
                                workspaceRoot.relativize(
                                        sourceFile
                                )
                        )
                );
            }

            if (methodRanges.size() > 1) {
                return ToolResult.failure(
                        """
                        동일한 이름의 메서드가 여러 개 발견되었습니다.
                        오버로드된 메서드는 현재 remove_method로 안전하게 삭제할 수 없습니다.

                        클래스 이름: %s
                        메서드 이름: %s
                        발견 개수: %d
                        파일 경로: %s
                        """.formatted(
                                className,
                                methodName,
                                methodRanges.size(),
                                workspaceRoot.relativize(
                                        sourceFile
                                )
                        )
                );
            }

            MethodRange methodRange =
                    methodRanges.getFirst();

            if (createBackup) {
                createBackup(
                        sourceFile
                );
            }

            String updatedSource =
                    source.substring(
                            0,
                            methodRange.start()
                    )
                            + source.substring(
                            methodRange.end()
                    );

            Files.writeString(
                    sourceFile,
                    updatedSource,
                    StandardCharsets.UTF_8
            );

            log.info(
                    "Remove Method Tool 실행 완료. className={}, methodName={}, path={}, backupCreated={}",
                    className,
                    methodName,
                    workspaceRoot.relativize(
                            sourceFile
                    ),
                    createBackup
            );

            return ToolResult.success(
                    """
                    메서드를 삭제했습니다.

                    클래스 이름: %s
                    메서드 이름: %s
                    파일 경로: %s
                    """.formatted(
                            className,
                            methodName,
                            workspaceRoot.relativize(
                                    sourceFile
                            )
                    )
            );

        } catch (Exception exception) {
            log.error(
                    "Remove Method Tool 실행 실패. className={}, methodName={}, path={}",
                    className,
                    methodName,
                    path,
                    exception
            );

            return ToolResult.failure(
                    "메서드 삭제 중 오류가 발생했습니다: "
                            + exception.getMessage()
            );
        }
    }

    private List<MethodRange> findMethodRanges(
            String source,
            String methodName
    ) {
        List<MethodRange> ranges =
                new ArrayList<>();

        Pattern pattern =
                Pattern.compile(
                        "\\b"
                                + Pattern.quote(methodName)
                                + "\\s*\\("
                );

        Matcher matcher =
                pattern.matcher(
                        source
                );

        while (matcher.find()) {
            int methodNameStart =
                    matcher.start();

            if (!isPossibleMethodDeclaration(
                    source,
                    methodNameStart
            )) {
                continue;
            }

            int parameterStart =
                    source.indexOf(
                            '(',
                            methodNameStart
                    );

            if (parameterStart < 0) {
                continue;
            }

            int parameterEnd =
                    findMatchingBracket(
                            source,
                            parameterStart,
                            '(',
                            ')'
                    );

            if (parameterEnd < 0) {
                continue;
            }

            int bodyStart =
                    findMethodBodyStart(
                            source,
                            parameterEnd + 1
                    );

            if (bodyStart < 0) {
                continue;
            }

            int bodyEnd =
                    findMatchingBracket(
                            source,
                            bodyStart,
                            '{',
                            '}'
                    );

            if (bodyEnd < 0) {
                continue;
            }

            int declarationStart =
                    findDeclarationStart(
                            source,
                            methodNameStart
                    );

            int removeStart =
                    findLineStart(
                            source,
                            declarationStart
                    );

            int removeEnd =
                    findLineEnd(
                            source,
                            bodyEnd + 1
                    );

            ranges.add(
                    new MethodRange(
                            removeStart,
                            removeEnd
                    )
            );
        }

        return ranges;
    }

    private boolean isPossibleMethodDeclaration(
            String source,
            int methodNameStart
    ) {
        int lineStart =
                source.lastIndexOf(
                        '\n',
                        Math.max(
                                0,
                                methodNameStart - 1
                        )
                );

        if (lineStart < 0) {
            lineStart = 0;
        }

        String prefix =
                source.substring(
                                lineStart,
                                methodNameStart
                        )
                        .trim();

        if (prefix.endsWith(".")) {
            return false;
        }

        if (prefix.startsWith("//")) {
            return false;
        }

        return true;
    }

    private int findMethodBodyStart(
            String source,
            int position
    ) {
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;

        for (
                int index = position;
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
                return index;
            }

            if (current == ';') {
                return -1;
            }
        }

        return -1;
    }

    private int findMatchingBracket(
            String source,
            int start,
            char open,
            char close
    ) {
        int depth = 0;

        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;

        for (
                int index = start;
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

            if (current == open) {
                depth++;
            }

            if (current == close) {
                depth--;

                if (depth == 0) {
                    return index;
                }
            }
        }

        return -1;
    }

    private int findDeclarationStart(
            String source,
            int methodNameStart
    ) {
        int currentLineStart =
                findLineStart(
                        source,
                        methodNameStart
                );

        int searchPosition =
                currentLineStart;

        while (searchPosition > 0) {
            int previousLineEnd =
                    searchPosition - 1;

            int previousLineStart =
                    findLineStart(
                            source,
                            previousLineEnd
                    );

            String previousLine =
                    source.substring(
                                    previousLineStart,
                                    previousLineEnd + 1
                            )
                            .trim();

            if (
                    previousLine.startsWith("@")
                            || previousLine.isBlank()
            ) {
                searchPosition =
                        previousLineStart;

                continue;
            }

            break;
        }

        return searchPosition;
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

    private record MethodRange(
            int start,
            int end
    ) {
    }
}