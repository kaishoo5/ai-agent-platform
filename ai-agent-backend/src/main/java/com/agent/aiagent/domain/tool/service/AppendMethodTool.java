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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppendMethodTool implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES =
            1_048_576L;

    private static final int MAX_CLASS_SEARCH_RESULTS =
            50;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "append_method",
                    "작업 폴더 안의 Java 클래스 마지막에 새로운 메서드를 추가합니다. "
                            + "methodCode에는 메서드 전체 선언과 구현을 전달해야 합니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "메서드를 추가할 Java 클래스 이름입니다. .java 확장자는 생략할 수 있습니다. 예: CalculatorTool",
                                    true
                            ),
                            "methodCode",
                            new ToolParameter(
                                    "string",
                                    "추가할 Java 메서드 전체 코드입니다. 예: public String getName() { return \"test\"; }",
                                    true
                            ),
                            "path",
                            new ToolParameter(
                                    "string",
                                    "검색을 시작할 작업 폴더 기준 상대 경로입니다. 생략하면 작업 폴더 최상위에서 검색합니다.",
                                    false
                            ),
                            "createBackup",
                            new ToolParameter(
                                    "boolean",
                                    "수정 전 .bak 백업 파일 생성 여부입니다. 생략하면 true입니다.",
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

        String methodCode =
                getStringArgument(
                        arguments,
                        "methodCode"
                );

        String requestedPath =
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
                    "메서드를 추가할 클래스 이름이 없습니다."
            );
        }

        if (methodCode == null) {
            return ToolResult.failure(
                    "추가할 메서드 코드가 없습니다."
            );
        }

        if (requestedPath == null) {
            requestedPath =
                    "";
        }

        String normalizedClassName =
                normalizeClassName(
                        className
                );

        String normalizedMethodCode =
                normalizeMethodCode(
                        methodCode
                );

        if (normalizedMethodCode == null) {
            return ToolResult.failure(
                    "올바른 Java 메서드 코드 형식이 아닙니다."
            );
        }

        try {
            Path workspaceRoot =
                    Path.of(
                                    fileSystemProperties.getRootDirectory()
                            )
                            .toAbsolutePath()
                            .normalize();

            Path searchRoot =
                    workspaceRoot.resolve(
                                    requestedPath
                            )
                            .normalize();

            if (!searchRoot.startsWith(workspaceRoot)) {
                return ToolResult.failure(
                        "작업 폴더 외부의 파일은 수정할 수 없습니다."
                );
            }

            if (!Files.exists(searchRoot)) {
                return ToolResult.failure(
                        "검색 경로가 존재하지 않습니다: "
                                + displayRequestedPath(
                                requestedPath
                        )
                );
            }

            if (!Files.isDirectory(searchRoot)) {
                return ToolResult.failure(
                        "검색 경로가 폴더가 아닙니다: "
                                + displayRequestedPath(
                                requestedPath
                        )
                );
            }

            List<Path> matchedClassFiles =
                    findJavaFiles(
                            searchRoot,
                            normalizedClassName
                    );

            if (matchedClassFiles.isEmpty()) {
                return ToolResult.failure(
                        "Java 클래스를 찾을 수 없습니다: "
                                + normalizedClassName
                );
            }

            if (matchedClassFiles.size() > 1) {
                return ToolResult.failure(
                        buildMultipleClassMatchesContent(
                                workspaceRoot,
                                normalizedClassName,
                                matchedClassFiles
                        )
                );
            }

            Path classFile =
                    matchedClassFiles.getFirst();

            if (!Files.isReadable(classFile)) {
                return ToolResult.failure(
                        "클래스 파일을 읽을 수 없습니다: "
                                + normalizePath(
                                workspaceRoot.relativize(
                                        classFile
                                )
                        )
                );
            }

            if (!Files.isWritable(classFile)) {
                return ToolResult.failure(
                        "클래스 파일을 수정할 수 없습니다: "
                                + normalizePath(
                                workspaceRoot.relativize(
                                        classFile
                                )
                        )
                );
            }

            long fileSize =
                    Files.size(
                            classFile
                    );

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return ToolResult.failure(
                        "클래스 파일이 너무 커서 수정할 수 없습니다. 최대 크기: "
                                + MAX_FILE_SIZE_BYTES
                                + " bytes, 실제 크기: "
                                + fileSize
                                + " bytes"
                );
            }

            String sourceCode =
                    Files.readString(
                            classFile,
                            StandardCharsets.UTF_8
                    );

            if (
                    containsMethod(
                            sourceCode,
                            normalizedMethodCode
                    )
            ) {
                return ToolResult.success(
                        "동일한 메서드 코드가 이미 존재합니다.\n"
                                + "className: "
                                + normalizedClassName
                );
            }

            String updatedSourceCode =
                    appendMethod(
                            sourceCode,
                            normalizedMethodCode
                    );

            if (updatedSourceCode == null) {
                return ToolResult.failure(
                        "클래스의 마지막 위치를 찾을 수 없습니다: "
                                + normalizedClassName
                );
            }

            Path backupFile =
                    null;

            if (createBackup) {
                backupFile =
                        createBackup(
                                classFile
                        );
            }

            Files.writeString(
                    classFile,
                    updatedSourceCode,
                    StandardCharsets.UTF_8
            );

            String relativePath =
                    normalizePath(
                            workspaceRoot.relativize(
                                    classFile
                            )
                    );

            log.info(
                    "Append Method Tool 실행 완료. className={}, path={}, backupCreated={}",
                    normalizedClassName,
                    relativePath,
                    createBackup
            );

            StringBuilder result =
                    new StringBuilder();

            result.append(
                    "Java 메서드 추가가 완료되었습니다."
            );

            result.append(
                    "\nclassName: "
            ).append(
                    normalizedClassName
            );

            result.append(
                    "\npath: "
            ).append(
                    relativePath
            );

            if (backupFile != null) {
                result.append(
                        "\nbackup: "
                ).append(
                        normalizePath(
                                workspaceRoot.relativize(
                                        backupFile
                                )
                        )
                );
            }

            return ToolResult.success(
                    result.toString()
            );
        } catch (IOException exception) {
            log.error(
                    "Append Method Tool 실행 실패. className={}, path={}",
                    className,
                    displayRequestedPath(
                            requestedPath
                    ),
                    exception
            );

            return ToolResult.failure(
                    "Java 메서드를 추가하는 중 파일 처리 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Append Method Tool 처리 실패. className={}, path={}",
                    className,
                    displayRequestedPath(
                            requestedPath
                    ),
                    exception
            );

            return ToolResult.failure(
                    "Java 메서드 추가 요청을 처리하는 중 오류가 발생했습니다."
            );
        }
    }

    private String appendMethod(
            String sourceCode,
            String methodCode
    ) {
        int classClosingBraceIndex =
                findClassClosingBraceIndex(
                        sourceCode
                );

        if (classClosingBraceIndex < 0) {
            return null;
        }

        String lineSeparator =
                detectLineSeparator(
                        sourceCode
                );

        String normalizedMethod =
                indentMultiline(
                        methodCode,
                        "    ",
                        lineSeparator
                );

        String before =
                sourceCode.substring(
                        0,
                        classClosingBraceIndex
                );

        String after =
                sourceCode.substring(
                        classClosingBraceIndex
                );

        before =
                trimTrailingWhitespace(
                        before
                );

        return before
                + lineSeparator
                + lineSeparator
                + normalizedMethod
                + lineSeparator
                + after;
    }

    private int findClassClosingBraceIndex(
            String sourceCode
    ) {
        int depth =
                0;

        boolean classStarted =
                false;

        boolean inString =
                false;

        boolean inChar =
                false;

        boolean inLineComment =
                false;

        boolean inBlockComment =
                false;

        boolean escaped =
                false;

        int classClosingBraceIndex =
                -1;

        for (int index = 0; index < sourceCode.length(); index++) {
            char current =
                    sourceCode.charAt(
                            index
                    );

            char next =
                    index + 1 < sourceCode.length()
                            ? sourceCode.charAt(
                            index + 1
                    )
                            : '\0';

            if (inLineComment) {
                if (
                        current == '\n'
                ) {
                    inLineComment =
                            false;
                }

                continue;
            }

            if (inBlockComment) {
                if (
                        current == '*'
                                && next == '/'
                ) {
                    inBlockComment =
                            false;
                    index++;
                }

                continue;
            }

            if (inString) {
                if (
                        current == '\\'
                                && !escaped
                ) {
                    escaped =
                            true;
                    continue;
                }

                if (
                        current == '"'
                                && !escaped
                ) {
                    inString =
                            false;
                }

                escaped =
                        false;

                continue;
            }

            if (inChar) {
                if (
                        current == '\\'
                                && !escaped
                ) {
                    escaped =
                            true;
                    continue;
                }

                if (
                        current == '\''
                                && !escaped
                ) {
                    inChar =
                            false;
                }

                escaped =
                        false;

                continue;
            }

            if (
                    current == '/'
                            && next == '/'
            ) {
                inLineComment =
                        true;
                index++;
                continue;
            }

            if (
                    current == '/'
                            && next == '*'
            ) {
                inBlockComment =
                        true;
                index++;
                continue;
            }

            if (
                    current == '"'
            ) {
                inString =
                        true;
                continue;
            }

            if (
                    current == '\''
            ) {
                inChar =
                        true;
                continue;
            }

            if (
                    current == '{'
            ) {
                depth++;

                if (!classStarted) {
                    classStarted =
                            true;
                }

                continue;
            }

            if (
                    current == '}'
                            && classStarted
            ) {
                depth--;

                if (depth == 0) {
                    classClosingBraceIndex =
                            index;
                }
            }
        }

        return classClosingBraceIndex;
    }

    private boolean containsMethod(
            String sourceCode,
            String methodCode
    ) {
        String normalizedSource =
                normalizeWhitespace(
                        sourceCode
                );

        String normalizedMethod =
                normalizeWhitespace(
                        methodCode
                );

        return normalizedSource.contains(
                normalizedMethod
        );
    }

    private String normalizeMethodCode(
            String methodCode
    ) {
        String normalized =
                methodCode.trim();

        if (
                normalized.startsWith(
                        "```"
                )
        ) {
            return null;
        }

        if (
                !normalized.contains(
                        "("
                )
                        || !normalized.contains(
                        ")"
                )
                        || !normalized.contains(
                        "{"
                )
                        || !normalized.endsWith(
                        "}"
                )
        ) {
            return null;
        }

        return normalized;
    }

    private String indentMultiline(
            String code,
            String indent,
            String lineSeparator
    ) {
        String normalized =
                code.replace(
                                "\r\n",
                                "\n"
                        )
                        .replace(
                                "\r",
                                "\n"
                        );

        String[] lines =
                normalized.split(
                        "\n",
                        -1
                );

        int minimumIndent =
                findMinimumIndent(
                        lines
                );

        StringBuilder builder =
                new StringBuilder();

        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                builder.append(
                        lineSeparator
                );
            }

            String line =
                    removeIndent(
                            lines[index],
                            minimumIndent
                    );

            if (!line.isBlank()) {
                builder.append(
                        indent
                );
            }

            builder.append(
                    line
            );
        }

        return builder.toString();
    }

    private int findMinimumIndent(
            String[] lines
    ) {
        int minimumIndent =
                Integer.MAX_VALUE;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }

            int indent =
                    0;

            while (
                    indent < line.length()
                            && Character.isWhitespace(
                            line.charAt(
                                    indent
                            )
                    )
            ) {
                indent++;
            }

            minimumIndent =
                    Math.min(
                            minimumIndent,
                            indent
                    );
        }

        return minimumIndent == Integer.MAX_VALUE
                ? 0
                : minimumIndent;
    }

    private String removeIndent(
            String line,
            int indent
    ) {
        if (
                indent <= 0
                        || line.isBlank()
        ) {
            return line;
        }

        int removeCount =
                Math.min(
                        indent,
                        line.length()
                );

        return line.substring(
                removeCount
        );
    }

    private String trimTrailingWhitespace(
            String value
    ) {
        int end =
                value.length();

        while (
                end > 0
                        && Character.isWhitespace(
                        value.charAt(
                                end - 1
                        )
                )
        ) {
            end--;
        }

        return value.substring(
                0,
                end
        );
    }

    private String normalizeWhitespace(
            String value
    ) {
        return value.replaceAll(
                        "\\s+",
                        " "
                )
                .trim();
    }

    private List<Path> findJavaFiles(
            Path searchRoot,
            String className
    ) throws IOException {
        String expectedFileName =
                className.toLowerCase(
                        Locale.ROOT
                )
                        + ".java";

        try (
                Stream<Path> stream =
                        Files.walk(
                                searchRoot
                        )
        ) {
            return stream.filter(
                            Files::isRegularFile
                    )
                    .filter(path ->
                            !isIgnoredPath(
                                    searchRoot,
                                    path
                            )
                    )
                    .filter(path ->
                            fileName(
                                    path
                            )
                                    .toLowerCase(
                                            Locale.ROOT
                                    )
                                    .equals(
                                            expectedFileName
                                    )
                    )
                    .sorted(
                            Comparator.comparing(path ->
                                    normalizePath(
                                            searchRoot.relativize(
                                                    path
                                            )
                                    )
                            )
                    )
                    .limit(
                            MAX_CLASS_SEARCH_RESULTS
                    )
                    .toList();
        }
    }

    private boolean isIgnoredPath(
            Path searchRoot,
            Path path
    ) {
        Path relativePath =
                searchRoot.relativize(
                        path
                );

        for (Path part : relativePath) {
            String name =
                    part.toString()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            if (
                    name.equals(
                            ".git"
                    )
                            || name.equals(
                            ".idea"
                    )
                            || name.equals(
                            "build"
                    )
                            || name.equals(
                            "target"
                    )
                            || name.equals(
                            "node_modules"
                    )       || name.equals(
                            ".ai-agent-transactions"
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    private String normalizeClassName(
            String className
    ) {
        String normalized =
                className.trim();

        if (
                normalized.toLowerCase(
                        Locale.ROOT
                ).endsWith(
                        ".java"
                )
        ) {
            normalized =
                    normalized.substring(
                            0,
                            normalized.length() - 5
                    );
        }

        return normalized;
    }

    private String fileName(
            Path path
    ) {
        Path fileName =
                path.getFileName();

        return fileName == null
                ? ""
                : fileName.toString();
    }

    private String detectLineSeparator(
            String sourceCode
    ) {
        if (
                sourceCode.contains(
                        "\r\n"
                )
        ) {
            return "\r\n";
        }

        return "\n";
    }

    private Path createBackup(
            Path classFile
    ) throws IOException {
        Path backupFile =
                classFile.resolveSibling(
                        classFile.getFileName()
                                + ".bak"
                );

        Files.copy(
                classFile,
                backupFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
        );

        return backupFile;
    }

    private boolean getBooleanArgument(
            Map<String, Object> arguments,
            String name,
            boolean defaultValue
    ) {
        if (
                arguments == null
                        || !arguments.containsKey(
                        name
                )
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

        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(
                    stringValue
            );
        }

        return defaultValue;
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

    private String displayRequestedPath(
            String requestedPath
    ) {
        if (
                requestedPath == null
                        || requestedPath.isBlank()
        ) {
            return ".";
        }

        return requestedPath;
    }

    private String normalizePath(
            Path path
    ) {
        return path.toString()
                .replace(
                        '\\',
                        '/'
                );
    }

    private String buildMultipleClassMatchesContent(
            Path workspaceRoot,
            String className,
            List<Path> matchedClassFiles
    ) {
        StringBuilder builder =
                new StringBuilder();

        builder.append(
                "동일한 클래스 이름의 Java 파일이 여러 개 발견되었습니다."
        );

        builder.append(
                "\nclassName: "
        ).append(
                className
        );

        builder.append(
                "\n검색 결과:"
        );

        for (Path path : matchedClassFiles) {
            builder.append(
                    "\n- "
            ).append(
                    normalizePath(
                            workspaceRoot.relativize(
                                    path
                            )
                    )
            );
        }

        builder.append(
                "\npath 값을 더 구체적으로 지정해주세요."
        );

        return builder.toString();
    }
}