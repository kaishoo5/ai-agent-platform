package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoTranscript;
import com.agent.aiagent.domain.video.model.VideoTranscriptSegment;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoSummaryService {

    private static final int SEGMENTS_PER_SUMMARY_BATCH = 50;
    private static final int MAX_SEGMENT_TEXT_LENGTH = 1_500;
    private static final int SUMMARY_CONCURRENCY = 3;

    private final ChatModelProvider chatModelProvider;

    public String summarize(
            VideoTranscript transcript
    ) {
        if (
                transcript == null
                        || transcript.segments() == null
                        || transcript.segments().isEmpty()
        ) {
            return "";
        }

        List<VideoTranscriptSegment> segments =
                transcript.segments()
                        .stream()
                        .filter(segment ->
                                segment != null
                                        && StringUtils.hasText(
                                        segment.text()
                                )
                        )
                        .toList();

        if (segments.isEmpty()) {
            return "";
        }

        List<String> partialSummaries =
                summarizeBatches(
                        segments
                );

        if (partialSummaries.isEmpty()) {
            return "";
        }

        if (partialSummaries.size() == 1) {
            return partialSummaries.getFirst();
        }

        String finalSummary =
                summarizeFinal(
                        partialSummaries
                );

        log.info(
                "영상 전체 요약 완료. segmentCount={}, partialSummaryCount={}, summaryLength={}",
                segments.size(),
                partialSummaries.size(),
                finalSummary.length()
        );

        return finalSummary;
    }

    private List<String> summarizeBatches(
            List<VideoTranscriptSegment> segments
    ) {
        ExecutorService executorService =
                Executors.newFixedThreadPool(
                        SUMMARY_CONCURRENCY
                );

        List<CompletableFuture<IndexedSummary>> futures =
                new ArrayList<>();

        try {
            int batchIndex =
                    0;

            for (
                    int start = 0;
                    start < segments.size();
                    start += SEGMENTS_PER_SUMMARY_BATCH
            ) {
                int end =
                        Math.min(
                                start + SEGMENTS_PER_SUMMARY_BATCH,
                                segments.size()
                        );

                List<VideoTranscriptSegment> batch =
                        List.copyOf(
                                segments.subList(
                                        start,
                                        end
                                )
                        );

                int currentBatchIndex =
                        batchIndex;

                int currentStart =
                        start;

                int currentEnd =
                        end;

                CompletableFuture<IndexedSummary> future =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    String summary =
                                            summarizeBatch(
                                                    batch,
                                                    currentStart,
                                                    currentEnd,
                                                    segments.size()
                                            );

                                    return new IndexedSummary(
                                            currentBatchIndex,
                                            summary
                                    );
                                },
                                executorService
                        );

                futures.add(
                        future
                );

                batchIndex++;
            }

            List<IndexedSummary> indexedSummaries =
                    new ArrayList<>();

            for (
                    CompletableFuture<IndexedSummary> future : futures
            ) {
                try {
                    IndexedSummary indexedSummary =
                            future.get();

                    if (
                            indexedSummary != null
                                    && StringUtils.hasText(
                                    indexedSummary.summary()
                            )
                    ) {
                        indexedSummaries.add(
                                indexedSummary
                        );
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread()
                            .interrupt();

                    throw new IllegalStateException(
                            "영상 부분 요약 처리 중 인터럽트가 발생했습니다.",
                            exception
                    );
                } catch (ExecutionException exception) {
                    Throwable cause =
                            exception.getCause();

                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }

                    throw new IllegalStateException(
                            "영상 부분 요약 처리 중 오류가 발생했습니다.",
                            cause
                    );
                }
            }

            indexedSummaries.sort(
                    Comparator.comparingInt(
                            IndexedSummary::index
                    )
            );

            return indexedSummaries
                    .stream()
                    .map(
                            IndexedSummary::summary
                    )
                    .toList();
        } finally {
            for (
                    CompletableFuture<IndexedSummary> future : futures
            ) {
                if (!future.isDone()) {
                    future.cancel(
                            true
                    );
                }
            }

            executorService.shutdownNow();
        }
    }

    private String summarizeBatch(
            List<VideoTranscriptSegment> batch,
            int start,
            int end,
            int total
    ) {
        StringBuilder transcriptPrompt =
                new StringBuilder();

        for (
                VideoTranscriptSegment segment : batch
        ) {
            transcriptPrompt.append("[")
                    .append(
                            formatTime(
                                    segment.startMillis()
                            )
                    )
                    .append(" ~ ")
                    .append(
                            formatTime(
                                    segment.endMillis()
                            )
                    )
                    .append("]\n");

            transcriptPrompt.append(
                            limitText(
                                    segment.text()
                            )
                    )
                    .append("\n\n");
        }

        String userPrompt =
                """
                다음은 하나의 영상에서 추출한 자막 일부입니다.

                이 구간에서 실제로 벌어지는 사건, 대화의 핵심, 등장인물의 행동과 관계를 요약하세요.

                규칙:
                1. 자막에 없는 내용을 추측하지 마세요.
                2. 단순히 문장을 나열하지 말고 자연스러운 이야기 흐름으로 요약하세요.
                3. 중요한 사건과 대화 내용을 우선하세요.
                4. 반복되는 대화나 의미 없는 감탄사는 생략하세요.
                5. 시간 정보는 중요한 장면을 설명할 때만 사용하세요.
                6. 한국어로 답변하세요.

                영상 자막:
                %s
                """.formatted(
                        transcriptPrompt
                );

        long startedAt =
                System.nanoTime();

        try {
            String summary =
                    chatModelProvider.chatOnce(
                            new ChatModelRequest(
                                    ChatModelType.TEXT,
                                    List.of(
                                            new ChatModelMessage(
                                                    "system",
                                                    """
                                                    당신은 영상 자막을 분석하여
                                                    해당 구간의 내용을 정확하고 간결하게 요약하는 도우미입니다.
                                                    """,
                                                    null
                                            ),
                                            new ChatModelMessage(
                                                    "user",
                                                    userPrompt,
                                                    null
                                            )
                                    ),
                                    List.of()
                            )
                    ).content();

            log.info(
                    "영상 부분 요약 완료. startSegment={}, endSegment={}, totalSegment={}, elapsed={}ms",
                    start,
                    end,
                    total,
                    elapsedMillis(
                            startedAt
                    )
            );

            return summary;
        } catch (Exception exception) {
            log.warn(
                    "영상 부분 요약 실패. startSegment={}, endSegment={}, totalSegment={}, elapsed={}ms",
                    start,
                    end,
                    total,
                    elapsedMillis(
                            startedAt
                    ),
                    exception
            );

            return "";
        }
    }

    private String summarizeFinal(
            List<String> partialSummaries
    ) {
        StringBuilder summaryPrompt =
                new StringBuilder();

        for (
                int index = 0;
                index < partialSummaries.size();
                index++
        ) {
            summaryPrompt.append("[구간 요약 ")
                    .append(index + 1)
                    .append("]\n");

            summaryPrompt.append(
                            partialSummaries.get(index)
                    )
                    .append("\n\n");
        }

        String userPrompt =
                """
                다음은 하나의 영상 전체를 여러 구간으로 나누어 요약한 결과입니다.

                이 내용을 바탕으로 영상 전체의 내용을 하나의 일관된 요약으로 다시 작성하세요.

                규칙:
                1. 각 구간 요약에 없는 내용을 추측하지 마세요.
                2. 사건의 순서와 이야기 흐름이 자연스럽게 이어지도록 작성하세요.
                3. 핵심 인물, 갈등, 사건, 결과를 중심으로 작성하세요.
                4. 중복되는 내용은 합치고 불필요한 반복은 제거하세요.
                5. 영상 전체의 내용을 이해할 수 있을 정도로 구체적으로 작성하세요.
                6. 한국어로 답변하세요.

                구간별 요약:
                %s
                """.formatted(
                        summaryPrompt
                );

        long startedAt =
                System.nanoTime();

        try {
            String summary =
                    chatModelProvider.chatOnce(
                            new ChatModelRequest(
                                    ChatModelType.TEXT,
                                    List.of(
                                            new ChatModelMessage(
                                                    "system",
                                                    """
                                                    당신은 여러 구간의 영상 요약을 통합하여
                                                    하나의 일관된 전체 영상 요약을 작성하는 도우미입니다.
                                                    """,
                                                    null
                                            ),
                                            new ChatModelMessage(
                                                    "user",
                                                    userPrompt,
                                                    null
                                            )
                                    ),
                                    List.of()
                            )
                    ).content();

            log.info(
                    "영상 최종 요약 완료. partialSummaryCount={}, elapsed={}ms",
                    partialSummaries.size(),
                    elapsedMillis(
                            startedAt
                    )
            );

            return summary;
        } catch (Exception exception) {
            log.warn(
                    "영상 최종 요약 실패. partialSummaryCount={}, elapsed={}ms",
                    partialSummaries.size(),
                    elapsedMillis(
                            startedAt
                    ),
                    exception
            );

            return String.join(
                    System.lineSeparator()
                            + System.lineSeparator(),
                    partialSummaries
            );
        }
    }

    private String limitText(
            String text
    ) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        if (text.length() <= MAX_SEGMENT_TEXT_LENGTH) {
            return text;
        }

        return text.substring(
                0,
                MAX_SEGMENT_TEXT_LENGTH
        ) + "...";
    }

    private String formatTime(
            long millis
    ) {
        long totalSeconds =
                millis / 1000;

        long hours =
                totalSeconds / 3600;

        long minutes =
                (totalSeconds % 3600) / 60;

        long seconds =
                totalSeconds % 60;

        if (hours > 0) {
            return String.format(
                    "%02d:%02d:%02d",
                    hours,
                    minutes,
                    seconds
            );
        }

        return String.format(
                "%02d:%02d",
                minutes,
                seconds
        );
    }

    private long elapsedMillis(
            long startedAt
    ) {
        return (
                System.nanoTime()
                        - startedAt
        ) / 1_000_000L;
    }

    private record IndexedSummary(
            int index,
            String summary
    ) {
    }
}