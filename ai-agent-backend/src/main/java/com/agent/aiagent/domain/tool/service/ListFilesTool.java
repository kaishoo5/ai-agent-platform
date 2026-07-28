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
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListFilesTool implements AgentTool {

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "list_files",
                    "지정된 작업 폴더 내부의 파일과 하위 폴더 목록을 조회합니다.",
                    Map.of(
                            "path",
                            new ToolParameter(
                                    "string",
                                    "작업 폴더를 기준으로 조회할 상대 경로입니다. 생략하면 최상위 폴더를 조회합니다.",
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
        String requestedPath =
                getRequestedPath(
                        arguments
                );

        try {
            Path rootPath =
                    Path.of(
                                    fileSystemProperties.getRootDirectory()
                            )
                            .toAbsolutePath()
                            .normalize();

            Path targetPath =
                    rootPath.resolve(
                                    requestedPath
                            )
                            .normalize();

            if (!targetPath.startsWith(rootPath)) {
                return ToolResult.failure(
                        "작업 폴더 외부의 경로에는 접근할 수 없습니다."
                );
            }

            if (!Files.exists(targetPath)) {
                return ToolResult.failure(
                        "경로가 존재하지 않습니다: "
                                + requestedPath
                );
            }

            if (!Files.isDirectory(targetPath)) {
                return ToolResult.failure(
                        "폴더 경로가 아닙니다: "
                                + requestedPath
                );
            }

            List<Path> paths;

            try (
                    Stream<Path> stream =
                            Files.list(
                                    targetPath
                            )
            ) {
                paths =
                        stream.sorted(
                                        Comparator
                                                .comparing(
                                                        (Path path) ->
                                                                !Files.isDirectory(path)
                                                )
                                                .thenComparing(
                                                        path ->
                                                                path.getFileName()
                                                                        .toString()
                                                                        .toLowerCase()
                                                )
                                )
                                .toList();
            }

            String content =
                    buildContent(
                            rootPath,
                            targetPath,
                            paths
                    );

            log.info(
                    "List Files Tool 실행 완료. path={}, count={}",
                    requestedPath,
                    paths.size()
            );

            return ToolResult.success(
                    content
            );
        } catch (IOException exception) {
            log.error(
                    "List Files Tool 실행 실패. path={}",
                    requestedPath,
                    exception
            );

            return ToolResult.failure(
                    "파일 목록을 조회하는 중 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "List Files Tool 처리 실패. path={}",
                    requestedPath,
                    exception
            );

            return ToolResult.failure(
                    "파일 경로를 처리하는 중 오류가 발생했습니다."
            );
        }
    }

    private String getRequestedPath(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return "";
        }

        Object path =
                arguments.get(
                        "path"
                );

        if (path == null) {
            return "";
        }

        return path.toString()
                .trim();
    }

    private String buildContent(
            Path rootPath,
            Path targetPath,
            List<Path> paths
    ) {
        String relativePath =
                rootPath.equals(targetPath)
                        ? "."
                        : rootPath.relativize(
                                targetPath
                        )
                        .toString();

        StringBuilder content =
                new StringBuilder();

        content.append("조회 경로: ")
                .append(relativePath)
                .append("\n");

        content.append("항목 수: ")
                .append(paths.size())
                .append("\n\n");

        if (paths.isEmpty()) {
            content.append("폴더가 비어 있습니다.");

            return content.toString();
        }

        for (Path path : paths) {
            boolean directory =
                    Files.isDirectory(
                            path
                    );

            content.append(
                            directory
                                    ? "[폴더] "
                                    : "[파일] "
                    )
                    .append(
                            path.getFileName()
                                    .toString()
                    );

            if (!directory) {
                try {
                    content.append(" (")
                            .append(
                                    Files.size(
                                            path
                                    )
                            )
                            .append(" bytes)");
                } catch (IOException exception) {
                    content.append(" (크기 확인 불가)");
                }
            }

            content.append("\n");
        }

        return content.toString()
                .trim();
    }
}