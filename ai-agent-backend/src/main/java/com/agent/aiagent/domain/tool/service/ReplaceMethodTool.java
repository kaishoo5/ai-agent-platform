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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReplaceMethodTool implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES =
            1_048_576L;

    private static final int MAX_CLASS_SEARCH_RESULTS =
            50;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "replace_method",
                    "작업 폴더 안의 Java 클래스에서 지정한 메서드 하나를 새로운 메서드 코드로 교체합니다. 반드시 기존 메서드를 확인한 뒤 사용해야 합니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "수정할 Java 클래스 이름입니다. .java 확장자는 생략할 수 있습니다. 예: ChatOrchestrator",
                                    true
                            ),
                            "methodName",
                            new ToolParameter(
                                    "string",
                                    "교체할 기존 메서드 이름입니다. 예: stream",
                                    true
                            ),
                            "newMethodCode",
                            new ToolParameter(
                                    "string",
                                    "기존 메서드를 대체할 완전한 Java 메서드 코드입니다. 접근 제어자, 반환 타입, 메서드명, 파라미터, 본문을 모두 포함하고 기존 프로젝트의 줄바꿈과 들여쓰기 형식을 유지해야 합니다.",
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

        String methodName =
                getStringArgument(
                        arguments,
                        "methodName"
                );

        String newMethodCode =
                getRawStringArgument(
                        arguments,
                        "newMethodCode"
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
                    "수정할 클래스 이름이 없습니다."
            );
        }

        if (methodName == null) {
            return ToolResult.failure(
                    "교체할 메서드 이름이 없습니다."
            );
        }

        if (
                newMethodCode == null
                        || newMethodCode.isBlank()
        ) {
            return ToolResult.failure(
                    "새로운 메서드 코드가 없습니다."
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

        String normalizedNewMethodCode =
                removeMarkdownCodeFence(
                        newMethodCode
                );

        if (
                !containsMethodDeclaration(
                        normalizedNewMethodCode,
                        methodName
                )
        ) {
            return ToolResult.failure(
                    "새로운 코드에서 메서드 선언을 확인할 수 없습니다. "
                            + methodName
                            + " 메서드의 전체 선언과 본문을 전달해야 합니다."
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
                        buildClassNotFoundContent(
                                requestedPath,
                                normalizedClassName
                        )
                );
            }

            if (matchedClassFiles.size() > 1) {
                return ToolResult.failure(
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

            if (methodMatches.size() > 1) {
                return ToolResult.failure(
                        buildMultipleMethodMatchesContent(
                                workspaceRoot,
                                classFile,
                                normalizedClassName,
                                methodName,
                                methodMatches
                        )
                );
            }

            MethodMatch methodMatch =
                    methodMatches.getFirst();

            String indentation =
                    extractIndentation(
                            sourceCode,
                            methodMatch.startIndex()
                    );

            String indentedNewMethodCode =
                    applyIndentation(
                            normalizedNewMethodCode,
                            indentation
                    );

            String updatedSourceCode =
                    sourceCode.substring(
                            0,
                            methodMatch.startIndex()
                    )
                            + indentedNewMethodCode
                            + sourceCode.substring(
                            methodMatch.endIndex()
                    );

            if (updatedSourceCode.equals(sourceCode)) {
                return ToolResult.success(
                        buildNoChangeContent(
                                workspaceRoot,
                                classFile,
                                normalizedClassName,
                                methodName
                        )
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
                    "Replace Method Tool 실행 완료. className={}, methodName={}, path={}, backupCreated={}",
                    normalizedClassName,
                    methodName,
                    relativePath,
                    createBackup
            );

            return ToolResult.success(
                    buildSuccessContent(
                            workspaceRoot,
                            classFile,
                            backupFile,
                            normalizedClassName,
                            methodName,
                            methodMatch,
                            indentedNewMethodCode
                    )
            );
        } catch (IOException exception) {
            log.error(
                    "Replace Method Tool 실행 실패. className={}, methodName={}, path={}",
                    className,
                    methodName,
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "Java 메서드를 교체하는 중 파일 처리 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Replace Method Tool 처리 실패. className={}, methodName={}, path={}",
                    className,
                    methodName,
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "메서드 교체 요청을 처리하는 중 오류가 발생했습니다."
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

        while (matcher.find()) {
            int declarationStart =
                    findDeclarationLineStart(
                            sourceCode,
                            matcher.start()
                    );

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

            if (maskedSource.charAt(bodyStart) == ';') {
                matches.add(
                        createMethodMatch(
                                sourceCode,
                                declarationStart,
                                bodyStart + 1
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
                            declarationStart,
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

        String code =
                sourceCode.substring(
                                startIndex,
                                endIndex
                        )
                        .stripTrailing();

        return new MethodMatch(
                startIndex,
                endIndex,
                startLine,
                endLine,
                code
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
                            masked.setCharAt(index + 1, ' ');
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
                            masked.setCharAt(index + 1, ' ');
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

    private boolean containsMethodDeclaration(
            String newMethodCode,
            String methodName
    ) {
        Pattern pattern =
                Pattern.compile(
                        "\\b"
                                + Pattern.quote(methodName)
                                + "\\s*\\("
                );

        return pattern.matcher(
                        maskCommentsAndStrings(
                                newMethodCode
                        )
                )
                .find();
    }

    private String extractIndentation(
            String sourceCode,
            int startIndex
    ) {
        int lineStart =
                sourceCode.lastIndexOf(
                        '\n',
                        Math.max(
                                startIndex - 1,
                                0
                        )
                )
                        + 1;

        StringBuilder indentation =
                new StringBuilder();

        for (
                int index = lineStart;
                index < sourceCode.length();
                index++
        ) {
            char current =
                    sourceCode.charAt(
                            index
                    );

            if (
                    current != ' '
                            && current != '\t'
            ) {
                break;
            }

            indentation.append(
                    current
            );
        }

        return indentation.toString();
    }

    private String applyIndentation(
            String methodCode,
            String indentation
    ) {
        String normalizedCode =
                methodCode.strip();

        String[] lines =
                normalizedCode.split(
                        "\\R",
                        -1
                );

        StringBuilder result =
                new StringBuilder();

        for (
                int index = 0;
                index < lines.length;
                index++
        ) {
            if (index > 0) {
                result.append("\n");
            }

            if (!lines[index].isBlank()) {
                result.append(indentation);
            }

            result.append(
                    lines[index]
            );
        }

        return result.toString();
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

    private String removeMarkdownCodeFence(
            String code
    ) {
        String trimmedCode =
                code.trim();

        if (!trimmedCode.startsWith("```")) {
            return trimmedCode;
        }

        int firstLineEnd =
                trimmedCode.indexOf('\n');

        if (firstLineEnd < 0) {
            return trimmedCode;
        }

        int closingFenceIndex =
                trimmedCode.lastIndexOf("```");

        if (closingFenceIndex <= firstLineEnd) {
            return trimmedCode;
        }

        return trimmedCode.substring(
                        firstLineEnd + 1,
                        closingFenceIndex
                )
                .strip();
    }

    private String buildSuccessContent(
            Path workspaceRoot,
            Path classFile,
            Path backupFile,
            String className,
            String methodName,
            MethodMatch originalMethod,
            String newMethodCode
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("Java 메서드 교체 완료\n\n");

        content.append("클래스 이름: ")
                .append(className)
                .append("\n");

        content.append("메서드 이름: ")
                .append(methodName)
                .append("\n");

        content.append("파일 경로: ")
                .append(
                        normalizePath(
                                workspaceRoot.relativize(
                                        classFile
                                )
                        )
                )
                .append("\n");

        content.append("기존 메서드 라인: ")
                .append(originalMethod.startLine())
                .append("-")
                .append(originalMethod.endLine())
                .append("\n");

        if (backupFile != null) {
            content.append("백업 파일: ")
                    .append(
                            normalizePath(
                                    workspaceRoot.relativize(
                                            backupFile
                                    )
                            )
                    )
                    .append("\n");
        }

        content.append("\n교체된 메서드:\n")
                .append("```java\n")
                .append(newMethodCode)
                .append(
                        newMethodCode.endsWith("\n")
                                ? ""
                                : "\n"
                )
                .append("```");

        return content.toString();
    }

    private String buildNoChangeContent(
            Path workspaceRoot,
            Path classFile,
            String className,
            String methodName
    ) {
        return new StringBuilder()
                .append("메서드 코드가 기존 코드와 동일하여 파일을 수정하지 않았습니다.\n\n")
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
                .toString();
    }

    private String buildMultipleMethodMatchesContent(
            Path workspaceRoot,
            Path classFile,
            String className,
            String methodName,
            List<MethodMatch> methodMatches
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("동일한 이름의 메서드가 여러 개 발견되어 수정하지 않았습니다.\n\n");

        content.append("클래스 이름: ")
                .append(className)
                .append("\n");

        content.append("메서드 이름: ")
                .append(methodName)
                .append("\n");

        content.append("파일 경로: ")
                .append(
                        normalizePath(
                                workspaceRoot.relativize(
                                        classFile
                                )
                        )
                )
                .append("\n");

        content.append("검색 결과 수: ")
                .append(methodMatches.size())
                .append("\n");

        for (
                int index = 0;
                index < methodMatches.size();
                index++
        ) {
            MethodMatch methodMatch =
                    methodMatches.get(index);

            content.append("\n- 후보 ")
                    .append(index + 1)
                    .append(": ")
                    .append(methodMatch.startLine())
                    .append("-")
                    .append(methodMatch.endLine())
                    .append(" 라인");
        }

        content.append("\n\n오버로딩된 메서드는 현재 자동 교체하지 않습니다.");

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

        content.append("동일한 클래스 이름을 가진 파일이 여러 개 발견되어 수정하지 않았습니다.\n\n");

        content.append("클래스 이름: ")
                .append(className)
                .append("\n");

        content.append("메서드 이름: ")
                .append(methodName)
                .append("\n");

        content.append("검색 결과 수: ")
                .append(matchedClassFiles.size())
                .append("\n");

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

        content.append("\n\n더 구체적인 path를 지정한 뒤 다시 호출해야 합니다.");

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
                            || name.equals(".ai-agent-transactions")
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

    private String getRawStringArgument(
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

        return value == null
                ? null
                : value.toString();
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

    private enum ParserState {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER
    }

    private record MethodMatch(
            int startIndex,
            int endIndex,
            int startLine,
            int endLine,
            String code
    ) {
    }
}