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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class FindClassReferenceTool implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES =
            1_048_576L;

    private static final int MAX_SCANNED_FILES =
            20_000;

    private static final int MAX_REFERENCE_RESULTS =
            200;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "find_class_references",
                    "작업 폴더 안의 Java 소스에서 특정 클래스가 사용된 위치를 검색합니다. import, 필드 타입, 파라미터 타입, 반환 타입, 생성자 호출 등의 참조를 파일 경로와 라인 번호로 반환합니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "참조 위치를 찾을 Java 클래스 이름입니다. .java 확장자는 생략할 수 있습니다. 예: ChatOrchestrator",
                                    true
                            ),
                            "path",
                            new ToolParameter(
                                    "string",
                                    "검색을 시작할 작업 폴더 기준 상대 경로입니다. 생략하면 작업 폴더 최상위에서 검색합니다.",
                                    false
                            ),
                            "includeDeclaration",
                            new ToolParameter(
                                    "boolean",
                                    "클래스 자체 선언 파일도 결과에 포함할지 여부입니다. 생략하면 false입니다.",
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

        String requestedPath =
                getStringArgument(
                        arguments,
                        "path"
                );

        boolean includeDeclaration =
                getBooleanArgument(
                        arguments,
                        "includeDeclaration",
                        false
                );

        if (className == null) {
            return ToolResult.failure(
                    "참조 위치를 찾을 클래스 이름이 없습니다."
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
                        "작업 폴더 외부에서는 클래스 참조를 검색할 수 없습니다."
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

            List<Path> javaFiles =
                    findJavaFiles(
                            searchRoot
                    );

            List<ClassReferenceMatch> matches =
                    findReferences(
                            searchRoot,
                            javaFiles,
                            normalizedClassName,
                            includeDeclaration
                    );

            String content =
                    buildContent(
                            workspaceRoot,
                            requestedPath,
                            normalizedClassName,
                            javaFiles.size(),
                            matches
                    );

            log.info(
                    "Find Class Reference Tool 실행 완료. className={}, path={}, scannedFileCount={}, resultCount={}, includeDeclaration={}",
                    normalizedClassName,
                    displayRequestedPath(requestedPath),
                    javaFiles.size(),
                    matches.size(),
                    includeDeclaration
            );

            return ToolResult.success(
                    content
            );
        } catch (IOException exception) {
            log.error(
                    "Find Class Reference Tool 실행 실패. className={}, path={}",
                    normalizedClassName,
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "클래스 참조를 검색하는 중 파일 처리 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Find Class Reference Tool 처리 실패. className={}, path={}",
                    normalizedClassName,
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "클래스 참조 검색 요청을 처리하는 중 오류가 발생했습니다."
            );
        }
    }

    private List<Path> findJavaFiles(
            Path searchRoot
    ) throws IOException {
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
                            fileName(path)
                                    .toLowerCase(
                                            Locale.ROOT
                                    )
                                    .endsWith(".java")
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
                            MAX_SCANNED_FILES
                    )
                    .toList();
        }
    }

    private List<ClassReferenceMatch> findReferences(
            Path searchRoot,
            List<Path> javaFiles,
            String className,
            boolean includeDeclaration
    ) throws IOException {
        Pattern classPattern =
                Pattern.compile(
                        "\\b"
                                + Pattern.quote(
                                className
                        )
                                + "\\b"
                );

        Pattern declarationPattern =
                Pattern.compile(
                        "\\b(?:class|interface|enum|record)\\s+"
                                + Pattern.quote(
                                className
                        )
                                + "\\b"
                );

        List<ClassReferenceMatch> matches =
                new ArrayList<>();

        for (Path javaFile : javaFiles) {
            if (matches.size() >= MAX_REFERENCE_RESULTS) {
                break;
            }

            long fileSize =
                    Files.size(
                            javaFile
                    );

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                continue;
            }

            String sourceCode =
                    Files.readString(
                            javaFile,
                            StandardCharsets.UTF_8
                    );

            String maskedSource =
                    maskCommentsAndStrings(
                            sourceCode
                    );

            Matcher matcher =
                    classPattern.matcher(
                            maskedSource
                    );

            while (
                    matcher.find()
                            && matches.size() < MAX_REFERENCE_RESULTS
            ) {
                int lineStart =
                        findLineStart(
                                sourceCode,
                                matcher.start()
                        );

                int lineEnd =
                        findLineEnd(
                                sourceCode,
                                matcher.end()
                        );

                String maskedLine =
                        maskedSource.substring(
                                        lineStart,
                                        lineEnd
                                )
                                .trim();

                if (
                        !includeDeclaration
                                && declarationPattern.matcher(
                                        maskedLine
                                )
                                .find()
                ) {
                    continue;
                }

                String lineContent =
                        sourceCode.substring(
                                        lineStart,
                                        lineEnd
                                )
                                .strip();

                int lineNumber =
                        calculateLineNumber(
                                sourceCode,
                                matcher.start()
                        );

                matches.add(
                        new ClassReferenceMatch(
                                searchRoot.relativize(
                                        javaFile
                                ),
                                lineNumber,
                                lineContent,
                                detectReferenceType(
                                        maskedLine,
                                        className
                                )
                        )
                );
            }
        }

        return matches;
    }

    private String detectReferenceType(
            String line,
            String className
    ) {
        String trimmedLine =
                line.trim();

        if (trimmedLine.startsWith("import ")) {
            return "IMPORT";
        }

        if (
                trimmedLine.matches(
                        ".*\\bnew\\s+"
                                + Pattern.quote(className)
                                + "\\s*\\(.*"
                )
        ) {
            return "CONSTRUCTOR";
        }

        if (
                trimmedLine.matches(
                        ".*\\bextends\\s+"
                                + Pattern.quote(className)
                                + "\\b.*"
                )
        ) {
            return "EXTENDS";
        }

        if (
                trimmedLine.matches(
                        ".*\\bimplements\\s+.*\\b"
                                + Pattern.quote(className)
                                + "\\b.*"
                )
        ) {
            return "IMPLEMENTS";
        }

        if (
                trimmedLine.matches(
                        ".*\\b"
                                + Pattern.quote(className)
                                + "\\s*\\.\\s*class\\b.*"
                )
        ) {
            return "CLASS_LITERAL";
        }

        if (
                trimmedLine.matches(
                        ".*@"
                                + Pattern.quote(className)
                                + "\\b.*"
                )
        ) {
            return "ANNOTATION";
        }

        return "REFERENCE";
    }

    private int findLineStart(
            String sourceCode,
            int position
    ) {
        return sourceCode.lastIndexOf(
                '\n',
                Math.max(
                        position - 1,
                        0
                )
        ) + 1;
    }

    private int findLineEnd(
            String sourceCode,
            int position
    ) {
        int lineEnd =
                sourceCode.indexOf(
                        '\n',
                        position
                );

        return lineEnd < 0
                ? sourceCode.length()
                : lineEnd;
    }

    private int calculateLineNumber(
            String sourceCode,
            int position
    ) {
        int lineNumber =
                1;

        int limit =
                Math.min(
                        position,
                        sourceCode.length()
                );

        for (
                int index = 0;
                index < limit;
                index++
        ) {
            if (sourceCode.charAt(index) == '\n') {
                lineNumber++;
            }
        }

        return lineNumber;
    }

    private String maskCommentsAndStrings(
            String sourceCode
    ) {
        StringBuilder masked =
                new StringBuilder(
                        sourceCode
                );

        ParserState state =
                ParserState.NORMAL;

        for (
                int index = 0;
                index < sourceCode.length();
                index++
        ) {
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

            switch (state) {
                case NORMAL -> {
                    if (
                            current == '/'
                                    && next == '/'
                    ) {
                        masked.setCharAt(index, ' ');
                        masked.setCharAt(index + 1, ' ');

                        index++;
                        state =
                                ParserState.LINE_COMMENT;

                        continue;
                    }

                    if (
                            current == '/'
                                    && next == '*'
                    ) {
                        masked.setCharAt(index, ' ');
                        masked.setCharAt(index + 1, ' ');

                        index++;
                        state =
                                ParserState.BLOCK_COMMENT;

                        continue;
                    }

                    if (current == '"') {
                        masked.setCharAt(index, ' ');

                        state =
                                ParserState.STRING;

                        continue;
                    }

                    if (current == '\'') {
                        masked.setCharAt(index, ' ');

                        state =
                                ParserState.CHARACTER;
                    }
                }

                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state =
                                ParserState.NORMAL;
                    } else {
                        masked.setCharAt(index, ' ');
                    }
                }

                case BLOCK_COMMENT -> {
                    if (
                            current == '*'
                                    && next == '/'
                    ) {
                        masked.setCharAt(index, ' ');
                        masked.setCharAt(index + 1, ' ');

                        index++;
                        state =
                                ParserState.NORMAL;
                    } else if (
                            current != '\n'
                                    && current != '\r'
                    ) {
                        masked.setCharAt(index, ' ');
                    }
                }

                case STRING -> {
                    if (
                            current == '\\'
                                    && next != '\0'
                    ) {
                        masked.setCharAt(index, ' ');

                        if (
                                next != '\n'
                                        && next != '\r'
                        ) {
                            masked.setCharAt(
                                    index + 1,
                                    ' '
                            );
                        }

                        index++;
                        continue;
                    }

                    if (current == '"') {
                        masked.setCharAt(index, ' ');

                        state =
                                ParserState.NORMAL;
                    } else if (
                            current != '\n'
                                    && current != '\r'
                    ) {
                        masked.setCharAt(index, ' ');
                    }
                }

                case CHARACTER -> {
                    if (
                            current == '\\'
                                    && next != '\0'
                    ) {
                        masked.setCharAt(index, ' ');

                        if (
                                next != '\n'
                                        && next != '\r'
                        ) {
                            masked.setCharAt(
                                    index + 1,
                                    ' '
                            );
                        }

                        index++;
                        continue;
                    }

                    if (current == '\'') {
                        masked.setCharAt(index, ' ');

                        state =
                                ParserState.NORMAL;
                    } else if (
                            current != '\n'
                                    && current != '\r'
                    ) {
                        masked.setCharAt(index, ' ');
                    }
                }
            }
        }

        return masked.toString();
    }

    private String buildContent(
            Path workspaceRoot,
            String requestedPath,
            String className,
            int scannedFileCount,
            List<ClassReferenceMatch> matches
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("Java 클래스 참조 검색 결과\n\n");

        content.append("클래스 이름: ")
                .append(className)
                .append("\n");

        content.append("검색 경로: ")
                .append(
                        displayRequestedPath(
                                requestedPath
                        )
                )
                .append("\n");

        content.append("검색한 Java 파일 수: ")
                .append(scannedFileCount)
                .append("\n");

        content.append("참조 결과 수: ")
                .append(matches.size())
                .append("\n");

        if (matches.isEmpty()) {
            content.append("\n참조 위치를 찾지 못했습니다.");

            return content.toString();
        }

        Path previousPath =
                null;

        for (ClassReferenceMatch match : matches) {
            if (
                    previousPath == null
                            || !previousPath.equals(
                            match.relativePath()
                    )
            ) {
                content.append("\n\n## ")
                        .append(
                                normalizePath(
                                        match.relativePath()
                                )
                        );

                previousPath =
                        match.relativePath();
            }

            content.append("\n\n- 라인 ")
                    .append(match.lineNumber())
                    .append(" [")
                    .append(match.referenceType())
                    .append("]\n")
                    .append("  `")
                    .append(
                            escapeMarkdownCode(
                                    match.lineContent()
                            )
                    )
                    .append("`");
        }

        if (matches.size() >= MAX_REFERENCE_RESULTS) {
            content.append("\n\n검색 결과는 최대 ")
                    .append(MAX_REFERENCE_RESULTS)
                    .append("개까지만 표시됩니다.");
        }

        return content.toString();
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
                    name.equals(".git")
                            || name.equals(".gradle")
                            || name.equals(".idea")
                            || name.equals("build")
                            || name.equals("target")
                            || name.equals("node_modules")
                            || name.equals("dist")
                            || name.equals("out")
            ) {
                return true;
            }
        }

        return false;
    }

    private String normalizeClassName(
            String className
    ) {
        String normalizedClassName =
                className.trim();

        if (
                normalizedClassName.toLowerCase(
                                Locale.ROOT
                        )
                        .endsWith(".java")
        ) {
            return normalizedClassName.substring(
                    0,
                    normalizedClassName.length() - ".java".length()
            );
        }

        return normalizedClassName;
    }

    private String getStringArgument(
            Map<String, Object> arguments,
            String key
    ) {
        if (arguments == null) {
            return null;
        }

        Object value =
                arguments.get(
                        key
                );

        if (value == null) {
            return null;
        }

        String text =
                value.toString()
                        .trim();

        return text.isBlank()
                ? null
                : text;
    }

    private boolean getBooleanArgument(
            Map<String, Object> arguments,
            String key,
            boolean defaultValue
    ) {
        if (
                arguments == null
                        || arguments.get(key) == null
        ) {
            return defaultValue;
        }

        Object value =
                arguments.get(
                        key
                );

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        return Boolean.parseBoolean(
                value.toString()
        );
    }

    private String fileName(
            Path path
    ) {
        return path.getFileName()
                .toString();
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

    private String displayRequestedPath(
            String requestedPath
    ) {
        return requestedPath == null
                || requestedPath.isBlank()
                ? "."
                : requestedPath;
    }

    private String escapeMarkdownCode(
            String text
    ) {
        return text.replace(
                "`",
                "\\`"
        );
    }

    private enum ParserState {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER
    }

    private record ClassReferenceMatch(
            Path relativePath,
            int lineNumber,
            String lineContent,
            String referenceType
    ) {
    }
}