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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class NavigateCodeTool implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES =
            1_048_576L;

    private static final int MAX_SEARCH_RESULTS =
            50;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "navigate_code",
                    "작업 폴더 안의 Java 클래스를 클래스 이름으로 찾아 파일 경로와 소스 코드를 읽습니다. 클래스 이름만 알고 있을 때 사용합니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "찾을 Java 클래스 이름입니다. .java 확장자는 생략할 수 있습니다. 예: ChatOrchestrator",
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

        String requestedPath =
                getArgument(
                        arguments,
                        "path"
                );

        if (className == null) {
            return ToolResult.failure(
                    "찾을 클래스 이름이 없습니다."
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
                        "작업 폴더 외부에서는 클래스를 검색할 수 없습니다."
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

            List<Path> matchedFiles =
                    findJavaFiles(
                            searchRoot,
                            normalizedClassName
                    );

            if (matchedFiles.isEmpty()) {
                return ToolResult.failure(
                        buildNotFoundContent(
                                requestedPath,
                                className
                        )
                );
            }

            if (matchedFiles.size() > 1) {
                return ToolResult.success(
                        buildMultipleMatchesContent(
                                workspaceRoot,
                                className,
                                matchedFiles
                        )
                );
            }

            Path matchedFile =
                    matchedFiles.getFirst();

            if (!Files.isReadable(matchedFile)) {
                return ToolResult.failure(
                        "파일을 읽을 수 없습니다: "
                                + normalizePath(
                                workspaceRoot.relativize(
                                        matchedFile
                                )
                        )
                );
            }

            long fileSize =
                    Files.size(
                            matchedFile
                    );

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return ToolResult.failure(
                        "파일 크기가 너무 커서 읽을 수 없습니다. 최대 크기: "
                                + MAX_FILE_SIZE_BYTES
                                + " bytes, 실제 크기: "
                                + fileSize
                                + " bytes"
                );
            }

            String sourceCode =
                    Files.readString(
                            matchedFile,
                            StandardCharsets.UTF_8
                    );

            String content =
                    buildFileContent(
                            workspaceRoot,
                            matchedFile,
                            normalizedClassName,
                            fileSize,
                            sourceCode
                    );

            log.info(
                    "Navigate Code Tool 실행 완료. className={}, path={}, fileSize={}",
                    normalizedClassName,
                    normalizePath(
                            workspaceRoot.relativize(
                                    matchedFile
                            )
                    ),
                    fileSize
            );

            return ToolResult.success(
                    content
            );
        } catch (IOException exception) {
            log.error(
                    "Navigate Code Tool 실행 실패. className={}, path={}",
                    className,
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "Java 클래스를 검색하거나 읽는 중 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Navigate Code Tool 처리 실패. className={}, path={}",
                    className,
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "클래스 탐색 요청을 처리하는 중 오류가 발생했습니다."
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
                            MAX_SEARCH_RESULTS
                    )
                    .toList();
        }
    }

    private String buildFileContent(
            Path workspaceRoot,
            Path matchedFile,
            String className,
            long fileSize,
            String sourceCode
    ) {
        String relativePath =
                normalizePath(
                        workspaceRoot.relativize(
                                matchedFile
                        )
                );

        return new StringBuilder()
                .append("Java 클래스 탐색 결과\n\n")
                .append("클래스 이름: ")
                .append(className)
                .append("\n")
                .append("파일 경로: ")
                .append(relativePath)
                .append("\n")
                .append("파일 크기: ")
                .append(fileSize)
                .append(" bytes\n\n")
                .append("소스 코드:\n")
                .append("```java\n")
                .append(sourceCode)
                .append(
                        sourceCode.endsWith("\n")
                                ? ""
                                : "\n"
                )
                .append("```")
                .toString();
    }

    private String buildMultipleMatchesContent(
            Path workspaceRoot,
            String className,
            List<Path> matchedFiles
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("동일한 클래스 이름을 가진 파일이 여러 개 발견되었습니다.\n\n");

        content.append("클래스 이름: ")
                .append(
                        normalizeClassName(
                                className
                        )
                )
                .append("\n");

        content.append("검색 결과 수: ")
                .append(matchedFiles.size())
                .append("\n\n");

        content.append("아래 경로 중 하나를 선택하여 path와 함께 다시 호출해야 합니다.\n");

        for (Path matchedFile : matchedFiles) {
            content.append("\n- ")
                    .append(
                            normalizePath(
                                    workspaceRoot.relativize(
                                            matchedFile
                                    )
                            )
                    );
        }

        if (matchedFiles.size() >= MAX_SEARCH_RESULTS) {
            content.append("\n\n검색 결과는 최대 ")
                    .append(MAX_SEARCH_RESULTS)
                    .append("개까지만 표시됩니다.");
        }

        return content.toString();
    }

    private String buildNotFoundContent(
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
                .append(
                        normalizeClassName(
                                className
                        )
                )
                .append("\n\n")
                .append("클래스 이름의 철자와 검색 경로를 확인해 주세요.")
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
}