package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoFullTextSummaryService {

    private static final int CHUNKS_PER_BATCH = 15;
    private final ChatFileChunkRepository chatFileChunkRepository;
    private final ChatModelProvider chatModelProvider;

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
            return ToolResult.failure("현재 첨부파일에서 영상 자막을 찾지 못했습니다.");
        }

        List<String> partialSummaries = new ArrayList<>();
        int totalBatches = (chunks.size() + CHUNKS_PER_BATCH - 1) / CHUNKS_PER_BATCH;

        for (int batch = 0; batch < totalBatches; batch++) {
            int from = batch * CHUNKS_PER_BATCH;
            int to = Math.min(from + CHUNKS_PER_BATCH, chunks.size());

            StringBuilder transcript = new StringBuilder();

            for (int i = from; i < to; i++) {
                ChatFileChunk chunk = chunks.get(i);

                if (chunk.getStartMillis() != null && chunk.getEndMillis() != null) {
                    transcript.append("[")
                            .append(formatTime(chunk.getStartMillis()))
                            .append(" ~ ")
                            .append(formatTime(chunk.getEndMillis()))
                            .append("] ");
                }
                // Gemini 분석 청크는 DB 시간 컬럼 대신 본문에 타임스탬프가 들어 있다.
                transcript.append(chunk.getContent()).append("\n");
            }

            String prompt = """
                    당신은 영상의 한 구간을 전체 줄거리 요약에 사용할 사건 기록으로 정리합니다.
                    아래 자막과 장면 분석에서 확인되는 내용만 사용하세요.

                    작성 원칙:
                    1. 장면이나 대사를 시간대별로 하나씩 나열하지 말고, 사건의 시작·진행·결과를 연결해 4~7문장으로 서술하세요.
                    2. 누가 무엇을 했고 그에 대해 상대방이 어떻게 반응했는지, 자료로 확인되는 관계만 연결하세요.
                    3. 다음 구간과 이어질 수 있는 미해결 사건, 인물의 상태 변화, 중요한 갈등을 놓치지 마세요.
                    4. 인물 이름이 서로 다르게 인식되거나 불명확하면 동일 인물이라고 임의로 합치지 말고 '한 학생', '상대방'처럼 표현하세요.
                    5. 문맥상 의미가 불명확한 STT 문구는 억지로 해석하거나 이야기의 핵심 사건으로 삼지 마세요.
                    6. 장면 분석에 없는 표정·행동·장소·동기·결과를 만들어내지 마세요.
                    7. 배경음악 가사, 반복 자막, 크레딧 등은 실제 사건 전개와 관계없으면 생략하세요.
                    8. 사건의 순서를 유지하되, 시간 표시는 꼭 필요한 사건에만 원문 타임스탬프 그대로 붙이세요.
                    9. 사건의 인과관계가 불확실하면 '때문에', '결국' 등 확정적인 연결 표현을 사용하지 마세요.
                    10. 자료가 부족하면 부족한 범위에서만 간결하게 요약하세요.
                    11. 입력 본문에 이미 타임스탬프가 있다면 그대로 사용하고, 없는 시간은 만들지 마세요.

                    영상 구간 자료:
                    """ + transcript;

            String summary = generate(prompt);

            if (summary.isBlank()) {
                return ToolResult.failure(
                        "영상 구간 요약에 실패했습니다. batch=" + (batch + 1)
                );
            }

            ChatFileChunk first = chunks.get(from);
            ChatFileChunk last = chunks.get(to - 1);

            String batchRange = first.getStartMillis() != null && last.getEndMillis() != null
                    ? "[" + formatTime(first.getStartMillis()) + " ~ "
                    + formatTime(last.getEndMillis()) + "]\n"
                    : "[분석 청크 " + (from + 1) + " ~ " + to + "]\n";
            partialSummaries.add(batchRange + summary);

            log.info(
                    "영상 전체 요약 구간 완료. roomId={}, batch={}/{}, chunks={}",
                    context.roomId(), batch + 1, totalBatches, to - from
            );
        }

        // 구간 요약만 통합 모델에 전달해 원본 자막 누적으로 인한 컨텍스트 폭증을 방지한다.
        String finalPrompt = """
                당신은 영상 전체의 사건 흐름을 독자가 이해할 수 있도록 정리하는 줄거리 요약가입니다.
                다음 자료는 하나의 영상을 시간 순서대로 나눈 구간 요약입니다.
                이 자료 전체를 읽고, 처음부터 끝까지 이어지는 자연스러운 한국어 줄거리로 다시 작성하세요.

                작성 원칙:
                1. 모든 구간을 검토하되, 구간 요약을 그대로 복사하거나 각 구간의 문장을 기계적으로 나열하지 마세요.
                2. 영상 초반의 상황, 갈등의 전개, 중요한 전환점, 후반부 결과를 시간 순서대로 연결하세요.
                3. 같은 사건이나 반복 대사는 합쳐서 서술하고, 사건에 영향을 주는 변화와 인물의 행동을 우선하세요.
                4. 전체를 4~7개의 의미 있는 이야기 단락으로 묶고, 각 단락에 짧은 소제목과 해당 자료에서 확인되는 시간 범위를 붙이세요.
                5. 각 단락은 단순한 장면 목록이 아니라 앞뒤 맥락이 이어지는 3~5문장 정도의 문단으로 작성하세요.
                6. 구간 요약에서 확인되지 않는 인물의 정체, 관계, 동기, 사건의 원인이나 결과는 만들어내지 마세요.
                7. STT 오류로 보이는 이름·문구를 억지로 교정하거나 서로 다른 이름을 같은 인물로 단정하지 마세요.
                   이름이 불확실하면 역할이나 상황을 중심으로 표현하고, 정말 필요한 경우에만 불확실성을 짧게 밝히세요.
                8. 의미가 불명확한 대사, 반복되는 가사, 크레딧을 줄거리의 주요 사건처럼 해석하지 마세요.
                9. 타임스탬프는 입력 자료에 있는 값을 그대로 사용하세요(HH:mm:ss 또는 mm:ss). 새 시간을 추측하거나 변환하지 마세요.
                   '[분석 청크 N ~ M]'은 시간 정보가 아니라 청크 순서이므로 시간으로 해석하지 마세요.
                10. 마지막에는 '전체 줄거리 요약'이라는 제목으로 핵심 흐름을 3~5문장으로 정리하세요.
                11. 구간별 요약 자체가 부정확하거나 사건 연결이 불명확한 경우, 추측으로 메우지 말고 확인되는 내용만 서술하세요.

                시간순 구간 요약:
                """ + String.join("\n\n", partialSummaries);

        String result = generate(finalPrompt);

        if (result.isBlank()) {
            return ToolResult.failure("전체 영상 통합 요약에 실패했습니다.");
        }

        log.info(
                "영상 전체 텍스트 요약 완료. roomId={}, totalChunks={}, batches={}",
                context.roomId(), chunks.size(), totalBatches
        );

        return ToolResult.success(result, Map.of(
                "fullVideoSummary", true,
                "totalChunks", chunks.size(),
                "batches", totalBatches
        ));
    }

    private String generate(String prompt) {
        ChatModelResponse response = chatModelProvider.chatOnce(
                new ChatModelRequest(
                        ChatModelType.TEXT,
                        List.of(
                                new ChatModelMessage(
                                        "system",
                                        "당신은 영상 자막 기반 요약기입니다. 제공된 내용만 근거로 답하세요."
                                ),
                                new ChatModelMessage("user", prompt)
                        ),
                        List.of()
                )
        );

        return response == null || response.content() == null
                ? ""
                : response.content().trim();
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
