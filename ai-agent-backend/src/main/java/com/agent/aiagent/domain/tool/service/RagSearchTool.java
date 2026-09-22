package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.service.EmbeddingFileChunkSearchService;
import com.agent.aiagent.domain.rag.model.RetrievedChunk;
import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagSearchTool implements AgentTool {

    private static final int TOP_K = 8;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "attachment_search",
                    """
                    현재 대화에 첨부된 파일의 실제 내용을 확인해야 할 때 사용합니다.
                    문서 내용, 영상의 자막이나 분석된 장면, 특정 내용이 파일에 존재하는지,
                    영상에서 특정 사건이나 장면이 언제 나오는지 등
                    첨부파일 내부 정보가 필요한 질문을 처리하기 위한 검색 도구입니다.
                    파일을 생성, 수정, 삭제하거나 요약 영상을 만드는 작업에는 사용하지 않습니다.
                    """.trim(),
                    Map.of(
                            "query",
                            new ToolParameter(
                                    "string",
                                    "첨부파일에서 찾을 내용이나 질문입니다.",
                                    true
                            )
                    )
            );

    private final EmbeddingFileChunkSearchService embeddingFileChunkSearchService;

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
        log.info(
                "Attachment Search Tool context 확인. roomId={}, fileIds={}, arguments={}",
                context == null
                        ? null
                        : context.roomId(),
                context == null
                        ? null
                        : context.fileIds(),
                arguments
        );

        String query =
                getQuery(
                        arguments
                );

        if (query == null) {
            return ToolResult.failure(
                    "첨부파일 검색 질문이 없습니다."
            );
        }

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

        try {
            List<RetrievedChunk> retrievedChunks =
                    embeddingFileChunkSearchService.search(
                            context.roomId(),
                            context.fileIds(),
                            query,
                            TOP_K
                    );

            if (retrievedChunks.isEmpty()) {
                return ToolResult.failure(
                        "첨부파일에서 관련 내용을 찾지 못했습니다."
                );
            }

            String content =
                    buildResultContent(
                            query,
                            retrievedChunks
                    );

            log.info(
                    "Attachment Search Tool 실행 완료. roomId={}, fileCount={}, query={}, resultCount={}",
                    context.roomId(),
                    context.fileIds().size(),
                    query,
                    retrievedChunks.size()
            );

            return ToolResult.success(
                    content
            );
        } catch (Exception exception) {
            log.error(
                    "Attachment Search Tool 실행 실패. roomId={}, query={}",
                    context.roomId(),
                    query,
                    exception
            );

            return ToolResult.failure(
                    "첨부파일 검색 중 오류가 발생했습니다."
            );
        }
    }

    private String getQuery(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return null;
        }

        Object query =
                arguments.get(
                        "query"
                );

        if (query == null) {
            return null;
        }

        String value =
                query.toString()
                        .trim();

        return value.isBlank()
                ? null
                : value;
    }

    private String buildResultContent(
            String query,
            List<RetrievedChunk> retrievedChunks
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("첨부파일 검색 질문: ")
                .append(query)
                .append("\n\n");

        for (
                int index = 0;
                index < retrievedChunks.size();
                index++
        ) {
            RetrievedChunk retrievedChunk =
                    retrievedChunks.get(index);

            ChatFileChunk chunk =
                    retrievedChunk.chunk();

            content.append("[첨부파일 검색 결과 ")
                    .append(index + 1)
                    .append("]\n");

            content.append("fileId: ")
                    .append(chunk.getFileId())
                    .append("\n");

            content.append("chunkIndex: ")
                    .append(chunk.getChunkIndex())
                    .append("\n");

            if (chunk.getStartMillis() != null) {
                content.append("startMillis: ")
                        .append(chunk.getStartMillis())
                        .append("\n");

                content.append("startSecond: ")
                        .append(chunk.getStartMillis() / 1000.0)
                        .append("\n");
            }

            if (chunk.getEndMillis() != null) {
                content.append("endMillis: ")
                        .append(chunk.getEndMillis())
                        .append("\n");

                content.append("endSecond: ")
                        .append(chunk.getEndMillis() / 1000.0)
                        .append("\n");
            }

            content.append("내용:\n")
                    .append(chunk.getContent())
                    .append("\n\n");
        }

        return content.toString()
                .trim();
    }
}