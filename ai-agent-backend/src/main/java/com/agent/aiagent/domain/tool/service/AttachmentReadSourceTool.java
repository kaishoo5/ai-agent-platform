package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentReadSourceTool implements AgentTool {

    private static final String SOURCE_PATH_PREFIX =
            "SOURCE_PATH:";

    private static final String SOURCE_EXTENSION_PREFIX =
            "SOURCE_EXTENSION:";

    private static final int MAX_CONTENT_LENGTH =
            20_000;

    private static final int MAX_OVERLAP_LENGTH =
            2_000;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "attachment_read_source",
                    """
                    현재 대화에 첨부된 ZIP 프로젝트에서
                    특정 소스 파일의 실제 전체 내용을 읽을 때 사용합니다.
    
                    sourcePath에는 반드시 ZIP 내부 소스 파일의 정확한 SOURCE_PATH를 전달하세요.
    
                    SOURCE_PATH는 attachment_project_structure,
                    attachment_codebase_overview 또는 attachment_search 결과에서
                    실제로 확인된 경로여야 합니다.
    
                    예:
                    - package.json
                    - src/main.js
                    - src/pages/Assets.vue
                    - src/store/actions.js
    
                    다음 값은 sourcePath로 절대 전달하지 마세요.
    
                    - 첨부파일 fileId
                    - UUID
                    - roomId
                    - 로컬 파일 시스템 경로
                    - 파일의 실제 저장 경로
                    - 아직 Tool Result에서 확인되지 않은 추측 경로
    
                    특히 다음과 같은 UUID 형식의 값은 SOURCE_PATH가 아닙니다.
    
                    잘못된 예:
                    - 498d92bc-50e9-4d95-a1c2-5925511ed9cb
    
                    정확한 SOURCE_PATH를 아직 모르는 경우에는
                    attachment_read_source를 호출하지 마세요.
    
                    먼저 attachment_project_structure 또는 attachment_search를 사용하여
                    실제 SOURCE_PATH를 확인한 뒤 이 도구를 호출하세요.
    
                    특정 파일의 실제 구현, 함수 본문, import, API 호출, 설정값,
                    컴포넌트 구현 또는 데이터 흐름을 정확히 확인해야 할 때 사용하세요.
    
                    파일명이나 검색 결과의 일부 코드만 보고 구현 내용을 추측하지 말고,
                    정확한 코드 확인이 필요한 경우 이 도구로 실제 파일 내용을 확인하세요.
                    """.trim(),
                    Map.of(
                            "sourcePath",
                            new ToolParameter(
                                    "string",
                                    """
                                    읽을 ZIP 내부 소스 파일의 정확한 SOURCE_PATH.
                                    fileId, UUID, roomId 또는 로컬 파일 경로를 전달하면 안 됩니다.
                                    SOURCE_PATH를 모르면 먼저 attachment_project_structure 또는
                                    attachment_search로 경로를 확인해야 합니다.
                                    """.trim(),
                                    true
                            )
                    )
            );

    private final ChatFileChunkRepository chatFileChunkRepository;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        return ToolResult.failure(
                "현재 대화의 첨부파일 정보가 없습니다."
        );
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments,
            ToolExecutionContext context
    ) {
        if (
                context == null
                        || context.roomId() == null
                        || context.roomId().isBlank()
        ) {
            return ToolResult.failure(
                    "현재 대화 정보를 확인할 수 없습니다."
            );
        }

        if (
                context.fileIds() == null
                        || context.fileIds().isEmpty()
        ) {
            return ToolResult.failure(
                    "현재 대화에 첨부된 파일이 없습니다."
            );
        }

        String sourcePath =
                getSourcePath(
                        arguments
                );

        if (sourcePath == null) {
            return ToolResult.failure(
                    "sourcePath가 필요합니다."
            );
        }

        try {
            List<ChatFileChunk> chunks =
                    chatFileChunkRepository
                            .findAllByRoomIdAndFileIdInOrderByFileIdAscChunkIndexAsc(
                                    context.roomId(),
                                    context.fileIds()
                            );

            List<SourceChunk> matchedChunks =
                    findSourceChunks(
                            chunks,
                            sourcePath
                    );

            if (matchedChunks.isEmpty()) {
                return ToolResult.failure(
                        "첨부 ZIP에서 SOURCE_PATH를 찾지 못했습니다: "
                                + sourcePath
                );
            }

            String actualSourcePath =
                    matchedChunks.getFirst()
                            .sourcePath();

            String sourceExtension =
                    matchedChunks.getFirst()
                            .sourceExtension();

            String sourceContent =
                    mergeSourceChunks(
                            matchedChunks
                    );

            boolean truncated =
                    sourceContent.length()
                            > MAX_CONTENT_LENGTH;

            if (truncated) {
                sourceContent =
                        sourceContent.substring(
                                0,
                                MAX_CONTENT_LENGTH
                        );
            }

            String content =
                    buildContent(
                            actualSourcePath,
                            sourceExtension,
                            sourceContent,
                            matchedChunks.size(),
                            truncated
                    );

            log.info(
                    "Attachment Read Source Tool 실행 완료. "
                            + "roomId={}, fileCount={}, sourcePath={}, chunkCount={}, contentLength={}, truncated={}",
                    context.roomId(),
                    context.fileIds().size(),
                    actualSourcePath,
                    matchedChunks.size(),
                    sourceContent.length(),
                    truncated
            );

            return ToolResult.success(
                    content,
                    Map.of(
                            "sourcePath",
                            actualSourcePath,
                            "chunkCount",
                            matchedChunks.size(),
                            "contentLength",
                            sourceContent.length(),
                            "truncated",
                            truncated
                    )
            );
        } catch (Exception exception) {
            log.error(
                    "Attachment Read Source Tool 실행 실패. "
                            + "roomId={}, fileIds={}, sourcePath={}",
                    context.roomId(),
                    context.fileIds(),
                    sourcePath,
                    exception
            );

            return ToolResult.failure(
                    "첨부 프로젝트의 소스 파일을 읽는 중 오류가 발생했습니다."
            );
        }
    }

    private String getSourcePath(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return null;
        }

        Object value =
                arguments.get(
                        "sourcePath"
                );

        if (value == null) {
            value =
                    arguments.get(
                            "source_path"
                    );
        }

        if (!(value instanceof String sourcePath)) {
            return null;
        }

        String normalized =
                normalizeSourcePath(
                        sourcePath
                );

        if (normalized.isBlank()) {
            return null;
        }

        return normalized;
    }

    private List<SourceChunk> findSourceChunks(
            List<ChatFileChunk> chunks,
            String requestedSourcePath
    ) {
        List<SourceChunk> matchedChunks =
                new ArrayList<>();

        for (ChatFileChunk chunk : chunks) {
            SourceChunk sourceChunk =
                    parseSourceChunk(
                            chunk
                    );

            if (sourceChunk == null) {
                continue;
            }

            if (
                    !sourceChunk.sourcePath()
                            .equals(
                                    requestedSourcePath
                            )
            ) {
                continue;
            }

            matchedChunks.add(
                    sourceChunk
            );
        }

        return matchedChunks;
    }

    private SourceChunk parseSourceChunk(
            ChatFileChunk chunk
    ) {
        if (
                chunk == null
                        || chunk.getContent() == null
                        || chunk.getContent().isBlank()
        ) {
            return null;
        }

        String content =
                chunk.getContent();

        String[] lines =
                content.split(
                        "\\R",
                        -1
                );

        String sourcePath =
                null;

        String sourceExtension =
                "";

        int sourceContentStartIndex =
                -1;

        for (
                int index = 0;
                index < lines.length;
                index++
        ) {
            String trimmed =
                    lines[index].trim();

            if (trimmed.startsWith(SOURCE_PATH_PREFIX)) {
                sourcePath =
                        normalizeSourcePath(
                                trimmed.substring(
                                                SOURCE_PATH_PREFIX.length()
                                        )
                                        .trim()
                        );

                continue;
            }

            if (trimmed.startsWith(SOURCE_EXTENSION_PREFIX)) {
                sourceExtension =
                        trimmed.substring(
                                        SOURCE_EXTENSION_PREFIX.length()
                                )
                                .trim()
                                .toLowerCase(
                                        Locale.ROOT
                                );

                continue;
            }

            if (
                    sourcePath != null
                            && trimmed.isEmpty()
            ) {
                sourceContentStartIndex =
                        index + 1;

                break;
            }
        }

        if (
                sourcePath == null
                        || sourcePath.isBlank()
        ) {
            return null;
        }

        if (sourceContentStartIndex < 0) {
            return null;
        }

        StringBuilder sourceContent =
                new StringBuilder();

        for (
                int index = sourceContentStartIndex;
                index < lines.length;
                index++
        ) {
            if (index > sourceContentStartIndex) {
                sourceContent.append("\n");
            }

            sourceContent.append(
                    lines[index]
            );
        }

        return new SourceChunk(
                sourcePath,
                sourceExtension,
                sourceContent.toString()
        );
    }

    private String mergeSourceChunks(
            List<SourceChunk> sourceChunks
    ) {
        if (sourceChunks.isEmpty()) {
            return "";
        }

        StringBuilder merged =
                new StringBuilder(
                        sourceChunks.getFirst()
                                .content()
                );

        for (
                int index = 1;
                index < sourceChunks.size();
                index++
        ) {
            String nextContent =
                    sourceChunks.get(index)
                            .content();

            int overlapLength =
                    findOverlapLength(
                            merged,
                            nextContent
                    );

            merged.append(
                    nextContent.substring(
                            overlapLength
                    )
            );
        }

        return merged.toString();
    }

    private int findOverlapLength(
            StringBuilder previousContent,
            String nextContent
    ) {
        if (
                previousContent.isEmpty()
                        || nextContent.isEmpty()
        ) {
            return 0;
        }

        int maxOverlapLength =
                Math.min(
                        Math.min(
                                previousContent.length(),
                                nextContent.length()
                        ),
                        MAX_OVERLAP_LENGTH
                );

        for (
                int overlapLength = maxOverlapLength;
                overlapLength > 0;
                overlapLength--
        ) {
            int previousStartIndex =
                    previousContent.length()
                            - overlapLength;

            if (
                    regionMatches(
                            previousContent,
                            previousStartIndex,
                            nextContent,
                            overlapLength
                    )
            ) {
                return overlapLength;
            }
        }

        return 0;
    }

    private boolean regionMatches(
            StringBuilder previousContent,
            int previousStartIndex,
            String nextContent,
            int length
    ) {
        for (
                int index = 0;
                index < length;
                index++
        ) {
            if (
                    previousContent.charAt(
                            previousStartIndex + index
                    )
                            != nextContent.charAt(
                            index
                    )
            ) {
                return false;
            }
        }

        return true;
    }

    private String buildContent(
            String sourcePath,
            String sourceExtension,
            String sourceContent,
            int chunkCount,
            boolean truncated
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("첨부 ZIP 실제 소스 파일\n\n")
                .append("SOURCE_PATH: ")
                .append(sourcePath)
                .append("\n")
                .append("SOURCE_EXTENSION: ")
                .append(sourceExtension)
                .append("\n")
                .append("CHUNK_COUNT: ")
                .append(chunkCount)
                .append("\n")
                .append("TRUNCATED: ")
                .append(truncated)
                .append("\n\n")
                .append("===== SOURCE CONTENT =====\n")
                .append(sourceContent)
                .append("\n===== END SOURCE CONTENT =====");

        if (truncated) {
            content.append(
                    "\n\n주의: 파일이 커서 앞부분만 반환되었습니다."
            );
        }

        content.append("\n\n[최종 응답 작성 규칙]\n")
                .append("- 위 SOURCE CONTENT는 현재 첨부 ZIP에서 읽은 실제 소스 내용입니다.\n")
                .append("- 위 코드에 존재하지 않는 함수, import, API 호출, 설정값을 임의로 추가하지 마세요.\n")
                .append("- 다른 파일의 구현이 필요하면 attachment_search로 위치를 찾은 뒤 attachment_read_source로 해당 파일을 읽으세요.\n")
                .append("- 확인되지 않은 구현을 예제 코드나 pseudo-code로 재구성하지 마세요.");

        return content.toString()
                .trim();
    }

    private String normalizeSourcePath(
            String sourcePath
    ) {
        String normalized =
                sourcePath
                        .trim()
                        .replace(
                                '\\',
                                '/'
                        );

        while (normalized.startsWith("./")) {
            normalized =
                    normalized.substring(2);
        }

        return normalized;
    }

    private record SourceChunk(
            String sourcePath,
            String sourceExtension,
            String content
    ) {
    }
}