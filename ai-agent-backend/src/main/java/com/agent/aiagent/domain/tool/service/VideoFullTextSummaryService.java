package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoFullTextSummaryService {

    private final ChatFileChunkRepository chatFileChunkRepository;

    public ToolResult summarize(ToolExecutionContext context) {
        List<ChatFileChunk> chunks = chatFileChunkRepository
                .findAllByRoomIdAndFileIdInOrderByFileIdAscChunkIndexAsc(
                        context.roomId(), context.fileIds())
                .stream()
                .filter(chunk -> chunk.getContent() != null
                        && !chunk.getContent().isBlank())
                .sorted(Comparator.comparing(ChatFileChunk::getFileId)
                        .thenComparingInt(ChatFileChunk::getChunkIndex))
                .toList();

        if (chunks.isEmpty()) {
            return ToolResult.failure("현재 첨부파일에서 영상 분석 내용을 찾지 못했습니다.");
        }

        // 원본 구간 분석을 다시 요약하면 인물, 장면, 대사 및 시간 정보가 사라질 수 있다.
        // fullVideo 검색에서는 저장된 분석 내용을 구간 순서대로 그대로 반환한다.
        StringBuilder result = new StringBuilder();
        result.append("# 영상 전체 구간별 상세 분석\n\n");
        result.append("아래 내용은 저장된 영상 분석 결과를 시간순으로 정리한 것입니다. ")
                .append("시간 범위는 DB에 저장된 각 구간의 시작·종료 시간입니다.\n\n");

        String previousFileId = null;
        int sectionNumber = 0;

        for (ChatFileChunk chunk : chunks) {
            String fileId = String.valueOf(chunk.getFileId());
            if (previousFileId != null && !previousFileId.equals(fileId)) {
                result.append("\n---\n\n");
                sectionNumber = 0;
            }
            previousFileId = fileId;
            sectionNumber++;

            result.append("## 구간 ").append(sectionNumber);
            if (chunk.getStartMillis() != null && chunk.getEndMillis() != null) {
                result.append(" (")
                        .append(formatTime(chunk.getStartMillis()))
                        .append(" ~ ")
                        .append(formatTime(chunk.getEndMillis()))
                        .append(")");
            } else {
                result.append(" (시간 정보 없음)");
            }
            result.append("\n\n")
                    .append(chunk.getContent().trim())
                    .append("\n\n");
        }

        String content = result.toString().trim();

        log.info(
                "영상 전체 구간별 상세 분석 반환 완료. roomId={}, totalChunks={}, contentLength={}",
                context.roomId(), chunks.size(), content.length()
        );

        return ToolResult.success(content, Map.of(
                "fullVideoSummary", true,
                "totalChunks", chunks.size(),
                "batches", 1
        ));
    }

    private String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);

        return String.format(
                "%02d:%02d:%02d",
                seconds / 3600,
                (seconds % 3600) / 60,
                seconds % 60
        );
    }
}
