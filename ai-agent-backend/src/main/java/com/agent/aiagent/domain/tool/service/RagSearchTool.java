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

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagSearchTool implements AgentTool {

    private static final int TOP_K = 8;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "attachment_search",
                    """
                    현재 대화에 첨부된 파일에서 관련 내용을 검색하고,
                    필요한 파일의 SOURCE_PATH를 찾기 위한 탐색 도구입니다.

                    일반 문서, 소스 코드, 영상 자막이나 분석된 장면 등에서
                    사용자의 질문과 관련된 내용을 찾을 때 사용합니다.

                    첨부 ZIP 프로젝트의 소스 코드를 분석하는 경우에는
                    이 도구를 실제 구현 전체를 읽는 용도로 사용하지 마세요.

                    ZIP 프로젝트 코드 분석 원칙:
                    - 필요한 파일의 SOURCE_PATH를 모를 때 attachment_search를 사용하세요.
                    - 검색 결과에서 필요한 파일의 정확한 SOURCE_PATH를 확인했다면
                      같은 파일을 찾기 위해 검색어만 바꾸어 attachment_search를 반복하지 마세요.
                    - 함수 본문, API 호출, import, 상태 변경, 라우팅, 데이터 흐름 등
                      실제 구현을 확인해야 한다면 SOURCE_PATH를 확인한 뒤
                      attachment_read_source로 해당 파일을 읽으세요.
                    - 정확한 SOURCE_PATH를 이미 알고 있다면 attachment_search를 호출하지 말고
                      attachment_read_source를 직접 사용하세요.
                    - attachment_search 결과의 일부 chunk만 보고
                      파일 전체 동작이나 호출 관계를 단정하지 마세요.

                    query에는 fileId, 파일 ID, UUID 같은 파일 식별자를 넣지 마세요.
                    첨부된 파일의 범위는 시스템이 자동으로 결정합니다.

                    예:
                    사용자 질문: "Sidebar.vue에서 메뉴 클릭 시 어디로 이동해?"
                    1. SOURCE_PATH를 모르면 query: "Sidebar.vue"
                    2. 검색 결과에서 SOURCE_PATH 확인
                    3. attachment_read_source로 해당 SOURCE_PATH의 실제 소스 확인

                    사용자 질문: "WebSearchTool이 어떻게 동작해?"
                    1. query: "WebSearchTool"
                    2. 검색 결과에서 SOURCE_PATH 확인
                    3. attachment_read_source로 실제 구현 확인

                    사용자 질문: "파일 업로드 처리가 어디서 이루어져?"
                    query: "파일 업로드 처리 ChatFile 업로드 서비스"

                    파일을 생성, 수정, 삭제하거나 요약 영상을 만드는 작업에는 사용하지 않습니다.
                    """.trim(),
                    Map.of(
                            "query",
                            new ToolParameter(
                                    "string",
                                    """
                                    첨부파일에서 관련 파일이나 내용을 찾기 위한 검색어입니다.
                                    fileId나 UUID를 넣지 마세요.
                                    ZIP 프로젝트 코드 검색에서는 파일명, 클래스명, 함수명,
                                    기능명 등 SOURCE_PATH를 찾는 데 필요한 핵심 검색어를 사용하세요.
                                    """.trim(),
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

            List<String> sourcePaths =
                    extractSourcePaths(
                            retrievedChunks
                    );

            String exactSourcePath =
                    selectUniqueExactSourcePath(
                            query,
                            sourcePaths
                    );

            if (exactSourcePath == null) {
                exactSourcePath =
                        selectUniqueExactContentSourcePath(
                                query,
                                retrievedChunks
                        );
            }

            Map<String, Object> metadata =
                    new LinkedHashMap<>();

            metadata.put(
                    "sourcePaths",
                    sourcePaths
            );

            /*
             * exactSourcePath는 "검색 점수가 제일 높은 파일"이 아니다.
             *
             * query가 파일명/경로와 명확하게 1:1로 일치할 때만 제공한다.
             * 예:
             *   ChatInput
             *       -> .../ChatInput.tsx
             *
             * 반대로 sendMessage, streamChat 같은 함수/심볼 검색은
             * 여러 파일에서 등장할 수 있으므로 exactSourcePath를 만들지 않는다.
             */
            if (exactSourcePath != null) {
                metadata.put(
                        "exactSourcePath",
                        exactSourcePath
                );
            }

            return ToolResult.success(
                    content,
                    metadata
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

    private String selectUniqueExactContentSourcePath(
            String query,
            List<RetrievedChunk> retrievedChunks
    ) {
        if (
                query == null
                        || query.isBlank()
                        || retrievedChunks == null
                        || retrievedChunks.isEmpty()
        ) {
            return null;
        }

        String normalizedQuery =
                normalizeCodeText(
                        query
                );

        if (normalizedQuery.length() < 3) {
            return null;
        }

        Set<String> matchedSourcePaths =
                new LinkedHashSet<>();

        for (RetrievedChunk retrievedChunk : retrievedChunks) {
            if (
                    retrievedChunk == null
                            || retrievedChunk.chunk() == null
                            || retrievedChunk.chunk().getContent() == null
            ) {
                continue;
            }

            String content =
                    retrievedChunk.chunk()
                            .getContent();

            String sourcePath =
                    extractSourcePath(
                            content
                    );

            if (
                    sourcePath == null
                            || sourcePath.isBlank()
            ) {
                continue;
            }

            String normalizedContent =
                    normalizeCodeText(
                            content
                    );

            if (
                    normalizedContent.contains(
                            normalizedQuery
                    )
            ) {
                matchedSourcePaths.add(
                        sourcePath
                );
            }
        }

        if (matchedSourcePaths.size() != 1) {
            return null;
        }

        return matchedSourcePaths.iterator()
                .next();
    }

    private String normalizeCodeText(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .toLowerCase(
                        Locale.ROOT
                )
                .replaceAll(
                        "\\s+",
                        ""
                )
                .trim();
    }

    private String selectUniqueExactSourcePath(
            String query,
            List<String> sourcePaths
    ) {
        if (
                query == null
                        || query.isBlank()
                        || sourcePaths == null
                        || sourcePaths.isEmpty()
        ) {
            return null;
        }

        String normalizedQuery =
                normalizeForMatch(
                        query
                );

        if (normalizedQuery.isBlank()) {
            return null;
        }

        List<String> exactMatches =
                sourcePaths.stream()
                        .filter(sourcePath ->
                                isExactSourcePathMatch(
                                        normalizedQuery,
                                        sourcePath
                                )
                        )
                        .distinct()
                        .toList();

        if (exactMatches.size() != 1) {
            return null;
        }

        return exactMatches.get(0);
    }

    private boolean isExactSourcePathMatch(
            String normalizedQuery,
            String sourcePath
    ) {
        if (
                sourcePath == null
                        || sourcePath.isBlank()
        ) {
            return false;
        }

        String normalizedPath =
                normalizeForMatch(
                        sourcePath
                );

        String fileName =
                getFileName(
                        normalizedPath
                );

        String fileNameWithoutExtension =
                removeFileExtension(
                        fileName
                );

        return normalizedQuery.equals(
                normalizedPath
        )
                || normalizedQuery.equals(
                fileName
        )
                || normalizedQuery.equals(
                fileNameWithoutExtension
        );
    }

    private String removeFileExtension(
            String fileName
    ) {
        if (
                fileName == null
                        || fileName.isBlank()
        ) {
            return "";
        }

        int extensionIndex =
                fileName.lastIndexOf(
                        '.'
                );

        if (extensionIndex <= 0) {
            return fileName;
        }

        return fileName.substring(
                0,
                extensionIndex
        );
    }

    private String selectBestSourcePath(
            String query,
            List<RetrievedChunk> retrievedChunks
    ) {
        if (
                query == null
                        || query.isBlank()
                        || retrievedChunks == null
                        || retrievedChunks.isEmpty()
        ) {
            return null;
        }

        String normalizedQuery =
                normalizeForMatch(
                        query
                );

        String queryFileName =
                getFileName(
                        normalizedQuery
                );

        Map<String, SourceCandidate> candidates =
                new LinkedHashMap<>();

        int rank = 0;

        for (RetrievedChunk retrievedChunk : retrievedChunks) {
            rank++;

            if (
                    retrievedChunk == null
                            || retrievedChunk.chunk() == null
                            || retrievedChunk.chunk().getContent() == null
            ) {
                continue;
            }

            String chunkContent =
                    retrievedChunk.chunk()
                            .getContent();

            String sourcePath =
                    extractSourcePath(
                            chunkContent
                    );

            if (
                    sourcePath == null
                            || sourcePath.isBlank()
            ) {
                continue;
            }

            SourceCandidate candidate =
                    candidates.get(
                            sourcePath
                    );

            if (candidate == null) {
                candidate =
                        new SourceCandidate(
                                sourcePath,
                                rank
                        );

                candidates.put(
                        sourcePath,
                        candidate
                );
            }

            String normalizedContent =
                    normalizeForMatch(
                            chunkContent
                    );

            if (
                    !normalizedQuery.isBlank()
                            && normalizedContent.contains(
                            normalizedQuery
                    )
            ) {
                candidate.exactContentMatchCount++;
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        SourceCandidate bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;

        for (SourceCandidate candidate : candidates.values()) {
            String normalizedPath =
                    normalizeForMatch(
                            candidate.sourcePath
                    );

            String fileName =
                    getFileName(
                            normalizedPath
                    );

            int score =
                    Math.max(
                            0,
                            100 - candidate.firstRank
                    );

            /*
             * 파일명이나 SOURCE_PATH를 직접 지정한 검색은 우선한다.
             * 확장자 종류에는 어떤 가산점/감점도 주지 않는다.
             */
            if (
                    !queryFileName.isBlank()
                            && fileName.equalsIgnoreCase(
                            queryFileName
                    )
            ) {
                score += 10_000;
            }

            if (
                    normalizedPath.equals(
                            normalizedQuery
                    )
                            || normalizedPath.endsWith(
                            "/" + normalizedQuery
                    )
            ) {
                score += 9_000;
            }

            /*
             * 실제 검색 chunk에 query 문자열이 존재하는 파일을 우선한다.
             * Java/TypeScript/CSS/XML/SQL/YAML 등 파일 종류는 구분하지 않는다.
             */
            score +=
                    candidate.exactContentMatchCount
                            * 1_500;

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }

        return bestCandidate == null
                ? null
                : bestCandidate.sourcePath;
    }

    private String extractSourcePath(
            String content
    ) {
        if (
                content == null
                        || content.isBlank()
        ) {
            return null;
        }

        String[] lines =
                content.split(
                        "\\R"
                );

        for (String line : lines) {
            String trimmed =
                    line.trim();

            if (!trimmed.startsWith("SOURCE_PATH:")) {
                continue;
            }

            String sourcePath =
                    trimmed.substring(
                                    "SOURCE_PATH:".length()
                            )
                            .trim();

            return sourcePath.isBlank()
                    ? null
                    : sourcePath;
        }

        return null;
    }

    private String normalizeForMatch(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value.trim()
                .replace(
                        '\\',
                        '/'
                )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private String getFileName(
            String path
    ) {
        if (
                path == null
                        || path.isBlank()
        ) {
            return "";
        }

        String normalized =
                path.replace(
                        '\\',
                        '/'
                );

        int separatorIndex =
                normalized.lastIndexOf(
                        '/'
                );

        return separatorIndex < 0
                ? normalized
                : normalized.substring(
                separatorIndex + 1
        );
    }

    private List<String> extractSourcePaths(
            List<RetrievedChunk> retrievedChunks
    ) {
        Set<String> sourcePaths =
                new LinkedHashSet<>();

        for (RetrievedChunk retrievedChunk : retrievedChunks) {
            if (
                    retrievedChunk == null
                            || retrievedChunk.chunk() == null
                            || retrievedChunk.chunk().getContent() == null
            ) {
                continue;
            }

            String[] lines =
                    retrievedChunk.chunk()
                            .getContent()
                            .split(
                                    "\\R"
                            );

            for (String line : lines) {
                String trimmed =
                        line.trim();

                if (!trimmed.startsWith("SOURCE_PATH:")) {
                    continue;
                }

                String sourcePath =
                        trimmed.substring(
                                        "SOURCE_PATH:".length()
                                )
                                .trim();

                if (!sourcePath.isBlank()) {
                    sourcePaths.add(
                            sourcePath
                    );
                }

                break;
            }
        }

        return List.copyOf(
                sourcePaths
        );
    }

    private String getQuery(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return null;
        }

        /*
         * Tool schema의 정식 인자는 query 하나다.
         * 일부 로컬 모델이 의미상 같은 alias를 생성하는 경우가 있어
         * 실행 단계에서만 호환한다.
         */
        Object query =
                firstNonNullArgument(
                        arguments,
                        "query",
                        "searchKeyword",
                        "keyword",
                        "q"
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

    private Object firstNonNullArgument(
            Map<String, Object> arguments,
            String... names
    ) {
        for (String name : names) {
            Object value =
                    arguments.get(
                            name
                    );

            if (value != null) {
                return value;
            }
        }

        return null;
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

        content.append("[다음 단계 안내]\n")
                .append("첨부 ZIP 프로젝트의 코드 검색이라면 위 결과에서 필요한 SOURCE_PATH를 확인하세요.\n")
                .append("SOURCE_PATH를 확인했고 함수 본문, API 호출, import, 상태 변경, 라우팅 또는 데이터 흐름 등 ")
                .append("실제 구현을 분석해야 한다면 attachment_read_source로 해당 파일을 읽으세요.\n")
                .append("같은 파일을 찾기 위해 검색어만 바꾸어 attachment_search를 반복하지 마세요.\n")
                .append("정확한 SOURCE_PATH를 이미 알고 있다면 attachment_search를 다시 호출하지 마세요.");

        return content.toString()
                .trim();
    }

    private static final class SourceCandidate {

        private final String sourcePath;

        private final int firstRank;

        private int exactContentMatchCount;


        private SourceCandidate(
                String sourcePath,
                int firstRank
        ) {
            this.sourcePath =
                    sourcePath;

            this.firstRank =
                    firstRank;
        }
    }
}