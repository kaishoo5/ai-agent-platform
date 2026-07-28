package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import com.agent.aiagent.infra.filesystem.FileSystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
public class SearchFilesTool implements AgentTool {

    private static final int MAX_SEARCH_RESULTS =
            100;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "search_files",
                    "작업 폴더와 하위 폴더에서 파일 이름이나 확장자로 파일을 검색합니다.",
                    Map.of(
                            "query",
                            new ToolParameter(
                                    "string",
                                    "검색할 파일 이름 또는 확장자입니다. 예: summary, test.txt, .java, *.txt",
                                    true
                            ),
                            "path",
                            new ToolParameter(
                                    "string",
                                    "검색을 시작할 작업 폴더 기준 상대 경로입니다. 생략하면 최상위 폴더부터 검색합니다.",
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
        String query =
                getArgument(
                        arguments,
                        "query"
                );

        String requestedPath =
                getArgument(
                        arguments,
                        "path"
                );

        if (query == null) {
            return ToolResult.failure(
                    "검색할 파일 이름 또는 확장자가 없습니다."
            );
        }

        if (requestedPath == null) {
            requestedPath =
                    "";
        }

        try {
            Path rootPath =
                    Path.of(
                                    fileSystemProperties.getRootDirectory()
                            )
                            .toAbsolutePath()
                            .normalize();

            Path searchPath =
                    rootPath.resolve(
                                    requestedPath
                            )
                            .normalize();

            if (!searchPath.startsWith(rootPath)) {
                return ToolResult.failure(
                        "작업 폴더 외부의 경로에서는 검색할 수 없습니다."
                );
            }

            if (!Files.exists(searchPath)) {
                return ToolResult.failure(
                        "검색 경로가 존재하지 않습니다: "
                                + requestedPath
                );
            }

            if (!Files.isDirectory(searchPath)) {
                return ToolResult.failure(
                        "검색 경로가 폴더가 아닙니다: "
                                + requestedPath
                );
            }

            String normalizedQuery =
                    normalizeQuery(
                            query
                    );

            List<Path> matchedFiles;

            try (
                    Stream<Path> stream =
                            Files.walk(
                                    searchPath
                            )
            ) {
                matchedFiles =
                        stream.filter(
                                        Files::isRegularFile
                                )
                                .filter(path ->
                                        matches(
                                                path,
                                                normalizedQuery
                                        )
                                )
                                .sorted(
                                        Comparator.comparing(
                                                path ->
                                                        rootPath.relativize(
                                                                        path
                                                                )
                                                                .toString()
                                                                .toLowerCase(
                                                                        Locale.ROOT
                                                                )
                                        )
                                )
                                .limit(
                                        MAX_SEARCH_RESULTS
                                )
                                .toList();
            }

            String content =
                    buildContent(
                            rootPath,
                            searchPath,
                            query,
                            matchedFiles
                    );

            log.info(
                    "Search Files Tool 실행 완료. path={}, query={}, resultCount={}",
                    requestedPath,
                    query,
                    matchedFiles.size()
            );

            return ToolResult.success(
                    content
            );
        } catch (IOException exception) {
            log.error(
                    "Search Files Tool 실행 실패. path={}, query={}",
                    requestedPath,
                    query,
                    exception
            );

            return ToolResult.failure(
                    "파일을 검색하는 중 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Search Files Tool 처리 실패. path={}, query={}",
                    requestedPath,
                    query,
                    exception
            );

            return ToolResult.failure(
                    "검색 경로를 처리하는 중 오류가 발생했습니다."
            );
        }
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

    private String normalizeQuery(
            String query
    ) {
        String normalizedQuery =
                query.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalizedQuery.startsWith("*.")) {
            return normalizedQuery.substring(
                    1
            );
        }

        return normalizedQuery;
    }

    private boolean matches(
            Path path,
            String normalizedQuery
    ) {
        String fileName =
                path.getFileName()
                        .toString()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return fileName.contains(
                normalizedQuery
        );
    }

    private String buildContent(
            Path rootPath,
            Path searchPath,
            String query,
            List<Path> matchedFiles
    ) {
        String relativeSearchPath =
                rootPath.equals(searchPath)
                        ? "."
                        : rootPath.relativize(
                                searchPath
                        )
                        .toString();

        StringBuilder content =
                new StringBuilder();

        content.append("검색 경로: ")
                .append(relativeSearchPath)
                .append("\n");

        content.append("검색어: ")
                .append(query)
                .append("\n");

        content.append("검색 결과 수: ")
                .append(matchedFiles.size())
                .append("\n\n");

        if (matchedFiles.isEmpty()) {
            content.append(
                    "검색 조건과 일치하는 파일이 없습니다."
            );

            return content.toString();
        }

        for (Path matchedFile : matchedFiles) {
            String relativePath =
                    rootPath.relativize(
                                    matchedFile
                            )
                            .toString();

            content.append("[파일] ")
                    .append(relativePath);

            try {
                content.append(" (")
                        .append(
                                Files.size(
                                        matchedFile
                                )
                        )
                        .append(" bytes)");
            } catch (IOException exception) {
                content.append(
                        " (크기 확인 불가)"
                );
            }

            content.append("\n");
        }

        if (matchedFiles.size() >= MAX_SEARCH_RESULTS) {
            content.append("\n")
                    .append("검색 결과는 최대 ")
                    .append(MAX_SEARCH_RESULTS)
                    .append("개까지만 표시됩니다.");
        }

        return content.toString()
                .trim();
    }
}