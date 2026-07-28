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
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReadFileTool implements AgentTool {

    private static final long MAX_FILE_SIZE =
            1024 * 1024;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "read_file",
                    "작업 폴더 내부의 텍스트 파일 내용을 읽습니다.",
                    Map.of(
                            "path",
                            new ToolParameter(
                                    "string",
                                    "읽을 파일의 작업 폴더 기준 상대 경로입니다.",
                                    true
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

        if (requestedPath == null) {
            return ToolResult.failure(
                    "읽을 파일 경로가 없습니다."
            );
        }

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
                        "작업 폴더 외부의 파일에는 접근할 수 없습니다."
                );
            }

            if (!Files.exists(targetPath)) {
                return ToolResult.failure(
                        "파일이 존재하지 않습니다: "
                                + requestedPath
                );
            }

            if (!Files.isRegularFile(targetPath)) {
                return ToolResult.failure(
                        "파일 경로가 아닙니다: "
                                + requestedPath
                );
            }

            long fileSize =
                    Files.size(
                            targetPath
                    );

            if (fileSize > MAX_FILE_SIZE) {
                return ToolResult.failure(
                        "파일 크기가 너무 큽니다. 읽을 수 있는 최대 크기는 1MB입니다."
                );
            }

            String fileContent =
                    Files.readString(
                            targetPath,
                            StandardCharsets.UTF_8
                    );

            String content =
                    buildContent(
                            rootPath,
                            targetPath,
                            fileSize,
                            fileContent
                    );

            log.info(
                    "Read File Tool 실행 완료. path={}, size={}",
                    requestedPath,
                    fileSize
            );

            return ToolResult.success(
                    content
            );
        } catch (IOException exception) {
            log.error(
                    "Read File Tool 실행 실패. path={}",
                    requestedPath,
                    exception
            );

            return ToolResult.failure(
                    "파일을 읽는 중 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Read File Tool 처리 실패. path={}",
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
            return null;
        }

        Object path =
                arguments.get(
                        "path"
                );

        if (path == null) {
            return null;
        }

        String value =
                path.toString()
                        .trim();

        return value.isBlank()
                ? null
                : value;
    }

    private String buildContent(
            Path rootPath,
            Path targetPath,
            long fileSize,
            String fileContent
    ) {
        String relativePath =
                rootPath.relativize(
                                targetPath
                        )
                        .toString();

        StringBuilder content =
                new StringBuilder();

        content.append("파일 경로: ")
                .append(relativePath)
                .append("\n");

        content.append("파일 크기: ")
                .append(fileSize)
                .append(" bytes")
                .append("\n\n");

        content.append("[파일 내용]")
                .append("\n")
                .append(fileContent);

        return content.toString()
                .trim();
    }
}