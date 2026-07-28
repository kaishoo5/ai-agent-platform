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
import java.nio.file.StandardOpenOption;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WriteFileTool implements AgentTool {

    private static final int MAX_CONTENT_LENGTH =
            1024 * 1024;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "write_file",
                    "작업 폴더 내부에 텍스트 파일을 생성하거나 기존 파일 내용을 덮어씁니다.",
                    Map.of(
                            "path",
                            new ToolParameter(
                                    "string",
                                    "저장할 파일의 작업 폴더 기준 상대 경로입니다.",
                                    true
                            ),
                            "content",
                            new ToolParameter(
                                    "string",
                                    "파일에 저장할 텍스트 내용입니다.",
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
                getRequiredString(
                        arguments,
                        "path"
                );

        String fileContent =
                getRequiredString(
                        arguments,
                        "content"
                );

        if (requestedPath == null) {
            return ToolResult.failure(
                    "저장할 파일 경로가 없습니다."
            );
        }

        if (fileContent == null) {
            return ToolResult.failure(
                    "파일에 저장할 내용이 없습니다."
            );
        }

        if (fileContent.length() > MAX_CONTENT_LENGTH) {
            return ToolResult.failure(
                    "저장할 내용이 너무 큽니다. 최대 1MB까지 저장할 수 있습니다."
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
                        "작업 폴더 외부의 파일에는 쓸 수 없습니다."
                );
            }

            if (targetPath.equals(rootPath)) {
                return ToolResult.failure(
                        "파일 경로를 입력해야 합니다."
                );
            }

            if (Files.exists(targetPath)
                    && Files.isDirectory(targetPath)) {
                return ToolResult.failure(
                        "파일 경로가 아닌 폴더 경로입니다: "
                                + requestedPath
                );
            }

            Path parentPath =
                    targetPath.getParent();

            if (parentPath != null) {
                Files.createDirectories(
                        parentPath
                );
            }

            boolean existed =
                    Files.exists(
                            targetPath
                    );

            Files.writeString(
                    targetPath,
                    fileContent,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            long fileSize =
                    Files.size(
                            targetPath
                    );

            String relativePath =
                    rootPath.relativize(
                                    targetPath
                            )
                            .toString();

            log.info(
                    "Write File Tool 실행 완료. path={}, size={}, overwritten={}",
                    relativePath,
                    fileSize,
                    existed
            );

            String result =
                    new StringBuilder()
                            .append(
                                    existed
                                            ? "파일을 덮어썼습니다."
                                            : "파일을 생성했습니다."
                            )
                            .append("\n")
                            .append("파일 경로: ")
                            .append(relativePath)
                            .append("\n")
                            .append("파일 크기: ")
                            .append(fileSize)
                            .append(" bytes")
                            .toString();

            return ToolResult.success(
                    result
            );
        } catch (IOException exception) {
            log.error(
                    "Write File Tool 실행 실패. path={}",
                    requestedPath,
                    exception
            );

            return ToolResult.failure(
                    "파일을 저장하는 중 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Write File Tool 처리 실패. path={}",
                    requestedPath,
                    exception
            );

            return ToolResult.failure(
                    "파일 경로를 처리하는 중 오류가 발생했습니다."
            );
        }
    }

    private String getRequiredString(
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
                value.toString();

        return text.isBlank()
                ? null
                : text;
    }
}