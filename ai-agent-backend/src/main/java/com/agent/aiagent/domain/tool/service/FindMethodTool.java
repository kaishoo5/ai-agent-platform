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
public class FindMethodTool implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES =
            1_048_576L;

    private static final int MAX_CLASS_SEARCH_RESULTS =
            50;

    private static final int MAX_METHOD_RESULTS =
            20;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "find_method",
                    "작업 폴더 안의 Java 클래스를 찾아 지정한 메서드의 선언부와 구현 코드를 반환합니다. 특정 메서드만 확인하거나 분석할 때 사용합니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "메서드가 선언된 Java 클래스 이름입니다. .java 확장자는 생략할 수 있습니다. 예: ChatOrchestrator",
                                    true
                            ),
                            "methodName",
                            new ToolParameter(
                                    "string",
                                    "찾을 메서드 이름입니다. 예: stream",
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
                getArgument(
                        arguments,
                        "className"
                );

        String methodName =
                getArgument(
                        arguments,
                        "methodName"
                );

        String requestedPath =
                getArgument(
                        arguments,
                        "path"
                );

        if (className == null) {
            return ToolResult.failure(
                    "메서드를 찾을 클래스 이름이 없습니다."
            );
        }

        if (methodName == null) {
            return ToolResult.failure(
                    "찾을 메서드 이름이 없습니다."
            );
        }

        if (requestedPath == null) {
            requestedPath =
                    "";
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
                        "작업 폴더 외부에서는 메서드를 검색할 수 없습니다."
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

            String normalizedClassName =
                    normalizeClassName(
                            className
                    );

            List<Path> matchedClassFiles =
                    findJavaFiles(
                            searchRoot,
                            normalizedClassName
                    );

            if (matchedClassFiles.isEmpty()) {
                return ToolResult.failure(
                        buildClassNotFoundContent(
                                requestedPath,
                                normalizedClassName
                        )
                );
            }

            if (matchedClassFiles.size() > 1) {
                return ToolResult.success(
                        buildMultipleClassMatchesContent(
                                workspaceRoot,
                                normalizedClassName,
                                methodName,
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

            long fileSize =
                    Files.size(
                            classFile
                    );

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return ToolResult.failure(
                        "클래스 파일이 너무 커서 메서드를 검색할 수 없습니다. 최대 크기: "
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

            List<MethodMatch> methodMatches =
                    findMethods(
                            sourceCode,
                            methodName
                    );

            if (methodMatches.isEmpty()) {
                return ToolResult.failure(
                        buildMethodNotFoundContent(
                                workspaceRoot,
                                classFile,
                                normalizedClassName,
                                methodName
                        )
                );
            }

            String content =
                    buildMethodContent(
                            workspaceRoot,
                            classFile,
                            normalizedClassName,
                            methodName,
                            methodMatches
                    );

            log.info(
                    "Find Method Tool 실행 완료. className={}, methodName={}, path={}, resultCount={}",
                    normalizedClassName,
                    methodName,
                    normalizePath(
                            workspaceRoot.relativize(
                                    classFile
                            )
                    ),
                    methodMatches.size()
            );

            return ToolResult.success(
                    content
            );
        } catch (IOException exception) {
            log.error(
                    "Find Method Tool 실행 실패. className={}, methodName={}, path={}",
                    className,
                    methodName,
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "Java 메서드를 검색하거나 읽는 중 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Find Method Tool 처리 실패. className={}, methodName={}, path={}",
                    className,
                    methodName,
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "메서드 탐색 요청을 처리하는 중 오류가 발생했습니다."
            );
        }
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
                            fileName(path)
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

    private List<MethodMatch> findMethods(
            String sourceCode,
            String methodName
    ) {
        String maskedSource =
                maskCommentsAndStrings(
                        sourceCode
                );

        Pattern methodPattern =
                Pattern.compile(
                        "(?m)^[\\t ]*"
                                + "(?:@[\\w$.]+(?:\\s*\\([^\\n]*\\))?[\\t ]*\\R[\\t ]*)*"
                                + "(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
                                + "(?:<[^>{};]+>\\s+)?"
                                + "(?:[\\w$<>\\[\\],.?]+\\s+)?"
                                + Pattern.quote(methodName)
                                + "\\s*\\("
                );

        Matcher matcher =
                methodPattern.matcher(
                        maskedSource
                );

        List<MethodMatch> matches =
                new ArrayList<>();

        while (
                matcher.find()
                        && matches.size() < MAX_METHOD_RESULTS
        ) {
            int declarationStart =
                    matcher.start();

            int parameterStart =
                    maskedSource.indexOf(
                            '(',
                            matcher.start()
                    );

            if (parameterStart < 0) {
                continue;
            }

            int parameterEnd =
                    findMatchingSymbol(
                            maskedSource,
                            parameterStart,
                            '(',
                            ')'
                    );

            if (parameterEnd < 0) {
                continue;
            }

            int bodyStart =
                    findMethodBodyStart(
                            maskedSource,
                            parameterEnd + 1
                    );

            if (bodyStart < 0) {
                continue;
            }

            int declarationLineStart =
                    findDeclarationLineStart(
                            sourceCode,
                            declarationStart
                    );

            if (maskedSource.charAt(bodyStart) == ';') {
                int methodEnd =
                        bodyStart + 1;

                matches.add(
                        createMethodMatch(
                                sourceCode,
                                declarationLineStart,
                                methodEnd
                        )
                );

                continue;
            }

            int bodyEnd =
                    findMatchingSymbol(
                            maskedSource,
                            bodyStart,
                            '{',
                            '}'
                    );

            if (bodyEnd < 0) {
                continue;
            }

            matches.add(
                    createMethodMatch(
                            sourceCode,
                            declarationLineStart,
                            bodyEnd + 1
                    )
            );
        }

        return matches;
    }

    private int findMethodBodyStart(
            String maskedSource,
            int startIndex
    ) {
        int parenthesesDepth =
                0;

        int angleBracketDepth =
                0;

        for (
                int index = startIndex;
                index < maskedSource.length();
                index++
        ) {
            char current =
                    maskedSource.charAt(
                            index
                    );

            if (current == '(') {
                parenthesesDepth++;
                continue;
            }

            if (current == ')') {
                if (parenthesesDepth > 0) {
                    parenthesesDepth--;
                }

                continue;
            }

            if (current == '<') {
                angleBracketDepth++;
                continue;
            }

            if (current == '>') {
                if (angleBracketDepth > 0) {
                    angleBracketDepth--;
                }

                continue;
            }

            if (
                    parenthesesDepth == 0
                            && angleBracketDepth == 0
                            && (
                            current == '{'
                                    || current == ';'
                    )
            ) {
                return index;
            }
        }

        return -1;
    }

    private int findMatchingSymbol(
            String source,
            int startIndex,
            char openSymbol,
            char closeSymbol
    ) {
        int depth =
                0;

        for (
                int index = startIndex;
                index < source.length();
                index++
        ) {
            char current =
                    source.charAt(
                            index
                    );

            if (current == openSymbol) {
                depth++;
                continue;
            }

            if (current != closeSymbol) {
                continue;
            }

            depth--;

            if (depth == 0) {
                return index;
            }
        }

        return -1;
    }

    private int findDeclarationLineStart(
            String sourceCode,
            int declarationStart
    ) {
        int currentLineStart =
                sourceCode.lastIndexOf(
                        '\n',
                        Math.max(
                                declarationStart - 1,
                                0
                        )
                )
                        + 1;

        int searchPosition =
                currentLineStart - 1;

        while (searchPosition > 0) {
            int previousLineStart =
                    sourceCode.lastIndexOf(
                            '\n',
                            searchPosition - 1
                    )
                            + 1;

            String previousLine =
                    sourceCode.substring(
                                    previousLineStart,
                                    searchPosition
                            )
                            .trim();

            if (
                    previousLine.startsWith("@")
                            || previousLine.startsWith("//")
                            || previousLine.startsWith("*")
                            || previousLine.startsWith("/*")
                            || previousLine.endsWith("*/")
            ) {
                currentLineStart =
                        previousLineStart;

                searchPosition =
                        previousLineStart - 1;

                continue;
            }

            break;
        }

        return currentLineStart;
    }

    private MethodMatch createMethodMatch(
            String sourceCode,
            int startIndex,
            int endIndex
    ) {
        String methodCode =
                sourceCode.substring(
                                startIndex,
                                endIndex
                        )
                        .stripTrailing();

        int startLine =
                calculateLineNumber(
                        sourceCode,
                        startIndex
                );

        int endLine =
                calculateLineNumber(
                        sourceCode,
                        Math.max(
                                endIndex - 1,
                                startIndex
                        )
                );

        return new MethodMatch(
                startLine,
                endLine,
                methodCode
        );
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
                        masked.setCharAt(
                                index,
                                ' '
                        );

                        masked.setCharAt(
                                index + 1,
                                ' '
                        );

                        index++;
                        state =
                                ParserState.LINE_COMMENT;

                        continue;
                    }

                    if (
                            current == '/'
                                    && next == '*'
                    ) {
                        masked.setCharAt(
                                index,
                                ' '
                        );

                        masked.setCharAt(
                                index + 1,
                                ' '
                        );

                        index++;
                        state =
                                ParserState.BLOCK_COMMENT;

                        continue;
                    }

                    if (current == '"') {
                        masked.setCharAt(
                                index,
                                ' '
                        );

                        state =
                                ParserState.STRING;

                        continue;
                    }

                    if (current == '\'') {
                        masked.setCharAt(
                                index,
                                ' '
                        );

                        state =
                                ParserState.CHARACTER;
                    }
                }

                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state =
                                ParserState.NORMAL;
                    } else {
                        masked.setCharAt(
                                index,
                                ' '
                        );
                    }
                }

                case BLOCK_COMMENT -> {
                    if (
                            current == '*'
                                    && next == '/'
                    ) {
                        masked.setCharAt(
                                index,
                                ' '
                        );

                        masked.setCharAt(
                                index + 1,
                                ' '
                        );

                        index++;
                        state =
                                ParserState.NORMAL;
                    } else if (
                            current != '\n'
                                    && current != '\r'
                    ) {
                        masked.setCharAt(
                                index,
                                ' '
                        );
                    }
                }

                case STRING -> {
                    if (
                            current == '\\'
                                    && next != '\0'
                    ) {
                        masked.setCharAt(
                                index,
                                ' '
                        );

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
                        masked.setCharAt(
                                index,
                                ' '
                        );

                        state =
                                ParserState.NORMAL;
                    } else if (
                            current != '\n'
                                    && current != '\r'
                    ) {
                        masked.setCharAt(
                                index,
                                ' '
                        );
                    }
                }

                case CHARACTER -> {
                    if (
                            current == '\\'
                                    && next != '\0'
                    ) {
                        masked.setCharAt(
                                index,
                                ' '
                        );

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
                        masked.setCharAt(
                                index,
                                ' '
                        );

                        state =
                                ParserState.NORMAL;
                    } else if (
                            current != '\n'
                                    && current != '\r'
                    ) {
                        masked.setCharAt(
                                index,
                                ' '
                        );
                    }
                }
            }
        }

        return masked.toString();
    }

    private String buildMethodContent(
            Path workspaceRoot,
            Path classFile,
            String className,
            String methodName,
            List<MethodMatch> methodMatches
    ) {
        String relativePath =
                normalizePath(
                        workspaceRoot.relativize(
                                classFile
                        )
                );

        StringBuilder content =
                new StringBuilder();

        content.append("Java 메서드 탐색 결과\n\n");

        content.append("클래스 이름: ")
                .append(className)
                .append("\n");

        content.append("메서드 이름: ")
                .append(methodName)
                .append("\n");

        content.append("파일 경로: ")
                .append(relativePath)
                .append("\n");

        content.append("검색 결과 수: ")
                .append(methodMatches.size())
                .append("\n");

        for (
                int index = 0;
                index < methodMatches.size();
                index++
        ) {
            MethodMatch match =
                    methodMatches.get(index);

            content.append("\n");

            if (methodMatches.size() > 1) {
                content.append("## 검색 결과 ")
                        .append(index + 1)
                        .append("\n\n");
            }

            content.append("라인: ")
                    .append(match.startLine())
                    .append("-")
                    .append(match.endLine())
                    .append("\n\n");

            content.append("```java\n")
                    .append(match.code())
                    .append(
                            match.code().endsWith("\n")
                                    ? ""
                                    : "\n"
                    )
                    .append("```");

            if (index < methodMatches.size() - 1) {
                content.append("\n");
            }
        }

        if (methodMatches.size() >= MAX_METHOD_RESULTS) {
            content.append("\n\n검색 결과는 최대 ")
                    .append(MAX_METHOD_RESULTS)
                    .append("개까지만 표시됩니다.");
        }

        return content.toString();
    }

    private String buildMultipleClassMatchesContent(
            Path workspaceRoot,
            String className,
            String methodName,
            List<Path> matchedClassFiles
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("동일한 클래스 이름을 가진 파일이 여러 개 발견되었습니다.\n\n");

        content.append("클래스 이름: ")
                .append(className)
                .append("\n");

        content.append("메서드 이름: ")
                .append(methodName)
                .append("\n");

        content.append("검색 결과 수: ")
                .append(matchedClassFiles.size())
                .append("\n\n");

        content.append("아래 경로 중 하나를 선택하여 더 구체적인 path로 다시 호출해야 합니다.\n");

        for (Path matchedClassFile : matchedClassFiles) {
            content.append("\n- ")
                    .append(
                            normalizePath(
                                    workspaceRoot.relativize(
                                            matchedClassFile
                                    )
                            )
                    );
        }

        if (
                matchedClassFiles.size()
                        >= MAX_CLASS_SEARCH_RESULTS
        ) {
            content.append("\n\n검색 결과는 최대 ")
                    .append(MAX_CLASS_SEARCH_RESULTS)
                    .append("개까지만 표시됩니다.");
        }

        return content.toString();
    }

    private String buildClassNotFoundContent(
            String requestedPath,
            String className
    ) {
        return new StringBuilder()
                .append("Java 클래스를 찾지 못했습니다.\n\n")
                .append("검색 경로: ")
                .append(
                        displayRequestedPath(
                                requestedPath
                        )
                )
                .append("\n")
                .append("클래스 이름: ")
                .append(className)
                .append("\n\n")
                .append("클래스 이름의 철자와 검색 경로를 확인해 주세요.")
                .toString();
    }

    private String buildMethodNotFoundContent(
            Path workspaceRoot,
            Path classFile,
            String className,
            String methodName
    ) {
        return new StringBuilder()
                .append("Java 클래스는 찾았지만 메서드를 찾지 못했습니다.\n\n")
                .append("클래스 이름: ")
                .append(className)
                .append("\n")
                .append("메서드 이름: ")
                .append(methodName)
                .append("\n")
                .append("파일 경로: ")
                .append(
                        normalizePath(
                                workspaceRoot.relativize(
                                        classFile
                                )
                        )
                )
                .append("\n\n")
                .append("메서드 이름의 철자를 확인해 주세요.")
                .toString();
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

    private String getArgument(
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

    private enum ParserState {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER
    }

    private record MethodMatch(
            int startLine,
            int endLine,
            String code
    ) {
    }
}