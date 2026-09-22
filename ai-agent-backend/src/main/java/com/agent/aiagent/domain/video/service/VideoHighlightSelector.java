package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
import com.agent.aiagent.domain.video.model.VideoHighlightSelection;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoHighlightSelector {

    private static final String VISION_PREFIX =
            "[화면 분석]";

    private static final long WINDOW_DURATION_MILLIS =
            10 * 60 * 1000L;

    private static final int MAX_CHUNK_CONTENT_LENGTH =
            1_500;

    private static final long MIN_CANDIDATE_DURATION_MILLIS =
            15_000L;

    private static final long MAX_CANDIDATE_DURATION_MILLIS =
            60_000L;

    private static final long MIN_PARTIAL_SEGMENT_MILLIS =
            5_000L;

    private static final long MERGE_GAP_MILLIS =
            3_000L;

    private static final int MAX_REASON_LENGTH =
            300;

    private static final int HIGHLIGHT_WINDOW_CONCURRENCY =
            2;

    /*
     * qwen이 JSON 대신 아래와 같은 텍스트를 반환하는 경우를 위한 fallback.
     *
     * 예:
     * (2070000 ~ 2100000 ms)
     */
    private static final Pattern MILLIS_RANGE_PATTERN =
            Pattern.compile(
                    "(\\d{5,})\\s*~\\s*(\\d{5,})"
            );

    public final ChatFileChunkRepository chatFileChunkRepository;
    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;

    public VideoHighlightSelection select(
            String fileId,
            long targetDurationMillis
    ) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException(
                    "fileId가 없습니다."
            );
        }

        if (targetDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "targetDurationMillis는 0보다 커야 합니다."
            );
        }

        List<ChatFileChunk> chunks =
                loadVideoChunks(
                        fileId
                );

        if (chunks.isEmpty()) {
            return new VideoHighlightSelection(
                    targetDurationMillis,
                    List.of()
            );
        }

        long videoEndMillis =
                chunks.stream()
                        .mapToLong(
                                ChatFileChunk::getEndMillis
                        )
                        .max()
                        .orElse(0L);

        log.info(
                "영상 하이라이트 선정 시작. fileId={}, chunkCount={}, videoEndMillis={}, targetDurationMillis={}",
                fileId,
                chunks.size(),
                videoEndMillis,
                targetDurationMillis
        );

        List<VideoHighlightSegment> rawCandidates =
                selectWindowCandidates(
                        chunks
                );

        if (rawCandidates.isEmpty()) {
            log.warn(
                    "영상 하이라이트 후보가 없습니다. fileId={}",
                    fileId
            );

            return new VideoHighlightSelection(
                    targetDurationMillis,
                    List.of()
            );
        }

        List<VideoHighlightSegment> normalizedCandidates =
                normalizeSegments(
                        rawCandidates,
                        videoEndMillis
                );

        if (normalizedCandidates.isEmpty()) {
            log.warn(
                    "정규화 후 영상 하이라이트 후보가 없습니다. fileId={}",
                    fileId
            );

            return new VideoHighlightSelection(
                    targetDurationMillis,
                    List.of()
            );
        }

        List<VideoHighlightSegment> mergedCandidates =
                mergeOverlappingSegments(
                        normalizedCandidates
                );

        log.info(
                "영상 하이라이트 후보 정리 완료. fileId={}, rawCandidateCount={}, normalizedCandidateCount={}, mergedCandidateCount={}",
                fileId,
                rawCandidates.size(),
                normalizedCandidates.size(),
                mergedCandidates.size()
        );

        List<VideoHighlightSegment> rankedCandidates =
                rerankCandidates(
                        mergedCandidates,
                        videoEndMillis,
                        targetDurationMillis
                );

        List<VideoHighlightSegment> selectedSegments =
                selectDistributedSegments(
                        rankedCandidates,
                        videoEndMillis,
                        targetDurationMillis
                );

        /*
         * 중요도 순으로 뽑은 뒤 실제 영상에서는
         * 원본의 시간 순서대로 재생한다.
         */
        selectedSegments =
                selectedSegments.stream()
                        .sorted(
                                Comparator.comparingLong(
                                        VideoHighlightSegment::startMillis
                                )
                        )
                        .toList();

        long selectedDurationMillis =
                calculateTotalDuration(
                        selectedSegments
                );

        log.info(
                "영상 하이라이트 선정 완료. fileId={}, candidateCount={}, selectedCount={}, targetDurationMillis={}, selectedDurationMillis={}",
                fileId,
                mergedCandidates.size(),
                selectedSegments.size(),
                targetDurationMillis,
                selectedDurationMillis
        );

        return new VideoHighlightSelection(
                targetDurationMillis,
                selectedSegments
        );
    }

    public String buildHighlightContext(
            String fileId
    ) {
        List<ChatFileChunk> chunks =
                loadVideoChunks(
                        fileId
                );

        return buildContext(
                chunks
        );
    }

    public List<VideoHighlightSegment> selectCandidates(
            String fileId
    ) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException(
                    "fileId가 없습니다."
            );
        }

        List<ChatFileChunk> chunks =
                loadVideoChunks(
                        fileId
                );

        if (chunks.isEmpty()) {
            return List.of();
        }

        long videoEndMillis =
                chunks.stream()
                        .mapToLong(
                                ChatFileChunk::getEndMillis
                        )
                        .max()
                        .orElse(0L);

        List<VideoHighlightSegment> rawCandidates =
                selectWindowCandidates(
                        chunks
                );

        if (rawCandidates.isEmpty()) {
            return List.of();
        }

        List<VideoHighlightSegment> normalizedCandidates =
                normalizeSegments(
                        rawCandidates,
                        videoEndMillis
                );

        if (normalizedCandidates.isEmpty()) {
            return List.of();
        }

        List<VideoHighlightSegment> mergedCandidates =
                mergeOverlappingSegments(
                        normalizedCandidates
                );

        log.info(
                "영상 하이라이트 후보 조회 완료. fileId={}, rawCandidateCount={}, normalizedCandidateCount={}, mergedCandidateCount={}",
                fileId,
                rawCandidates.size(),
                normalizedCandidates.size(),
                mergedCandidates.size()
        );

        return mergedCandidates;
    }

    private List<ChatFileChunk> loadVideoChunks(
            String fileId
    ) {
        return chatFileChunkRepository.findAllByFileId(
                        fileId
                )
                .stream()
                .filter(
                        this::isValidVideoChunk
                )
                .sorted(
                        Comparator
                                .comparing(
                                        ChatFileChunk::getStartMillis
                                )
                                .thenComparing(
                                        ChatFileChunk::getChunkIndex
                                )
                )
                .toList();
    }

    private List<VideoHighlightSegment> selectWindowCandidates(
            List<ChatFileChunk> chunks
    ) {
        long videoEndMillis =
                chunks.stream()
                        .mapToLong(
                                ChatFileChunk::getEndMillis
                        )
                        .max()
                        .orElse(0L);

        ExecutorService executorService =
                Executors.newFixedThreadPool(
                        HIGHLIGHT_WINDOW_CONCURRENCY
                );

        List<CompletableFuture<WindowCandidateResult>> futures =
                new ArrayList<>();

        try {
            for (
                    long windowStart = 0;
                    windowStart < videoEndMillis;
                    windowStart += WINDOW_DURATION_MILLIS
            ) {
                long currentWindowStart =
                        windowStart;

                long currentWindowEnd =
                        Math.min(
                                currentWindowStart
                                        + WINDOW_DURATION_MILLIS,
                                videoEndMillis
                        );

                List<ChatFileChunk> windowChunks =
                        findWindowChunks(
                                chunks,
                                currentWindowStart,
                                currentWindowEnd
                        );

                if (windowChunks.isEmpty()) {
                    continue;
                }

                CompletableFuture<WindowCandidateResult> future =
                        CompletableFuture.supplyAsync(
                                () ->
                                        selectWindowCandidates(
                                                windowChunks,
                                                currentWindowStart,
                                                currentWindowEnd
                                        ),
                                executorService
                        );

                futures.add(
                        future
                );
            }

            List<WindowCandidateResult> windowResults =
                    new ArrayList<>();

            for (
                    CompletableFuture<WindowCandidateResult> future
                    : futures
            ) {
                try {
                    WindowCandidateResult result =
                            future.get();

                    if (result != null) {
                        windowResults.add(
                                result
                        );
                    }
                } catch (Exception exception) {
                    log.warn(
                            "영상 하이라이트 window 병렬 처리 결과 조회 실패.",
                            exception
                    );
                }
            }

            windowResults.sort(
                    Comparator.comparingLong(
                            WindowCandidateResult::windowStart
                    )
            );

            List<VideoHighlightSegment> candidates =
                    new ArrayList<>();

            for (WindowCandidateResult result : windowResults) {
                candidates.addAll(
                        result.candidates()
                );
            }

            return candidates;
        } finally {
            for (
                    CompletableFuture<WindowCandidateResult> future
                    : futures
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

    private WindowCandidateResult selectWindowCandidates(
            List<ChatFileChunk> windowChunks,
            long windowStart,
            long windowEnd
    ) {
        try {
            log.info(
                    "영상 하이라이트 window 후보 선정 시작. windowStart={}, windowEnd={}",
                    windowStart,
                    windowEnd
            );

            long startedAt =
                    System.nanoTime();

            List<VideoHighlightSegment> windowCandidates =
                    selectCandidatesFromWindow(
                            windowChunks,
                            windowStart,
                            windowEnd
                    );

            List<VideoHighlightSegment> adjustedCandidates =
                    adjustWindowCandidates(
                            windowCandidates,
                            windowStart,
                            windowEnd
                    );

            long elapsedMillis =
                    (
                            System.nanoTime()
                                    - startedAt
                    )
                            / 1_000_000L;

            log.info(
                    "영상 하이라이트 구간 후보 선정 완료. windowStart={}, windowEnd={}, rawCandidateCount={}, validCandidateCount={}, elapsedMillis={}",
                    windowStart,
                    windowEnd,
                    windowCandidates.size(),
                    adjustedCandidates.size(),
                    elapsedMillis
            );

            return new WindowCandidateResult(
                    windowStart,
                    windowEnd,
                    adjustedCandidates
            );
        } catch (Exception exception) {
            log.warn(
                    "영상 하이라이트 구간 후보 선정 실패. windowStart={}, windowEnd={}",
                    windowStart,
                    windowEnd,
                    exception
            );

            return new WindowCandidateResult(
                    windowStart,
                    windowEnd,
                    List.of()
            );
        }
    }

    private List<ChatFileChunk> findWindowChunks(
            List<ChatFileChunk> chunks,
            long windowStart,
            long windowEnd
    ) {
        return chunks.stream()
                .filter(chunk ->
                        chunk.getStartMillis() < windowEnd
                                && chunk.getEndMillis() > windowStart
                )
                .toList();
    }

    private List<VideoHighlightSegment> selectCandidatesFromWindow(
            List<ChatFileChunk> chunks,
            long windowStart,
            long windowEnd
    ) {
        String context =
                buildContext(
                        chunks
                );

        String userPrompt =
                """
                다음은 하나의 영상에서 특정 영상 구간의 자막과 화면 분석 결과입니다.

                이 구간에서 전체 영상의 줄거리와 핵심 사건을 요약하는 데
                반드시 볼 가치가 있는 장면만 선정하세요.

                단순히 화면이 바뀌거나 사람이 등장한다는 이유만으로 선정하면 안 됩니다.

                선정 우선순위:
                1. 이야기의 방향이 바뀌는 사건
                2. 중요한 갈등, 결정, 폭로, 위기, 해결
                3. 인물 관계나 상황을 이해하는 데 필요한 핵심 대화
                4. 이후 사건의 원인이나 결과가 되는 행동
                5. 강한 감정 변화가 실제 자막이나 상황으로 확인되는 장면
                6. 전체 줄거리를 이해하는 데 반드시 필요한 장면

                선정하지 말아야 할 장면:
                - 단순 이동
                - 평범한 일상 대화
                - 단순 인물 등장
                - 배경이나 장소만 바뀌는 장면
                - 화면에 로고나 사물이 보인다는 이유만으로 중요한 장면
                - 자막이나 문맥으로 확인되지 않는 추측성 장면
                - 엔딩 크레딧
                - 제작진 정보
                - 반복 장면

                시간 규칙:
                - 반드시 아래 분석 데이터에 표시된 실제 timestamp를 사용하세요.
                - 현재 분석 범위 밖의 시간을 만들지 마세요.
                - 각 후보는 가능하면 15초 이상 60초 이하로 선정하세요.
                - 중요한 사건 하나를 이해하는 데 필요한 연속 구간을 선정하세요.

                현재 분석 범위:
                %d ~ %d 밀리초
                (%s ~ %s)

                출력 규칙:
                - JSON 배열만 반환하세요.
                - 설명 문장을 JSON 앞뒤에 추가하지 마세요.
                - startMillis와 endMillis는 반드시 위 분석 범위 안의 절대 밀리초 값이어야 합니다.
                - 중요한 장면이 없으면 []을 반환하세요.
                - 억지로 후보 개수를 채우지 마세요.

                형식:
                [
                  {
                    "startMillis": 120000,
                    "endMillis": 150000,
                    "reason": "이 장면이 전체 줄거리에서 중요한 이유"
                  }
                ]

                영상 분석 데이터:
                %s
                """.formatted(
                        windowStart,
                        windowEnd,
                        formatTime(
                                windowStart
                        ),
                        formatTime(
                                windowEnd
                        ),
                        context
                );

        ChatModelResponse response =
                chatModelProvider.chatOnce(
                        new ChatModelRequest(
                                ChatModelType.TEXT,
                                List.of(
                                        new ChatModelMessage(
                                                "system",
                                                """
                                                당신은 영화, 드라마, 다큐멘터리 등
                                                긴 영상의 핵심 줄거리를 짧은 영상으로 편집하는 전문 편집자입니다.

                                                단순히 시각적으로 눈에 띄는 장면이 아니라
                                                이야기 전체를 이해하는 데 중요한 사건과 대화를 선정하세요.

                                                제공되지 않은 내용을 추측하지 마세요.
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
                );

        if (
                response == null
                        || !StringUtils.hasText(
                        response.content()
                )
        ) {
            return List.of();
        }

        return parseSegments(
                response.content()
        );
    }

    private List<VideoHighlightSegment> adjustWindowCandidates(
            List<VideoHighlightSegment> candidates,
            long windowStart,
            long windowEnd
    ) {
        if (
                candidates == null
                        || candidates.isEmpty()
        ) {
            return List.of();
        }

        List<VideoHighlightSegment> adjusted =
                new ArrayList<>();

        long windowDuration =
                windowEnd
                        - windowStart;

        for (VideoHighlightSegment candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            long startMillis =
                    candidate.startMillis();

            long endMillis =
                    candidate.endMillis();

            /*
             * 정상적인 absolute timestamp.
             */
            if (
                    startMillis >= windowStart
                            && endMillis <= windowEnd
                            && endMillis > startMillis
            ) {
                if (
                        isValidCandidateDuration(
                                startMillis,
                                endMillis
                        )
                ) {
                    adjusted.add(
                            candidate
                    );
                } else {
                    log.warn(
                            "영상 하이라이트 비정상 길이 후보 제거. startMillis={}, endMillis={}, durationMillis={}, windowStart={}, windowEnd={}",
                            startMillis,
                            endMillis,
                            endMillis - startMillis,
                            windowStart,
                            windowEnd
                    );
                }

                continue;
            }

            /*
             * qwen이 절대 timestamp 대신 현재 5분 window를
             * 0부터 시작하는 상대 timestamp로 반환했을 때만 보정한다.
             */
            if (
                    startMillis >= 0
                            && endMillis > startMillis
                            && startMillis <= windowDuration
                            && endMillis <= windowDuration
            ) {
                long adjustedStartMillis =
                        windowStart
                                + startMillis;

                long adjustedEndMillis =
                        windowStart
                                + endMillis;

                if (
                        adjustedStartMillis >= windowStart
                                && adjustedEndMillis <= windowEnd
                                && isValidCandidateDuration(
                                adjustedStartMillis,
                                adjustedEndMillis
                        )
                ) {
                    log.info(
                            "영상 하이라이트 상대 timestamp 보정. originalStart={}, originalEnd={}, adjustedStart={}, adjustedEnd={}, windowStart={}, windowEnd={}",
                            startMillis,
                            endMillis,
                            adjustedStartMillis,
                            adjustedEndMillis,
                            windowStart,
                            windowEnd
                    );

                    adjusted.add(
                            new VideoHighlightSegment(
                                    adjustedStartMillis,
                                    adjustedEndMillis,
                                    candidate.reason()
                            )
                    );
                } else {
                    log.warn(
                            "영상 하이라이트 상대 timestamp 보정 후 비정상 후보 제거. originalStart={}, originalEnd={}, adjustedStart={}, adjustedEnd={}, windowStart={}, windowEnd={}",
                            startMillis,
                            endMillis,
                            adjustedStartMillis,
                            adjustedEndMillis,
                            windowStart,
                            windowEnd
                    );
                }

                continue;
            }

            log.warn(
                    "영상 하이라이트 잘못된 timestamp 후보 제거. startMillis={}, endMillis={}, windowStart={}, windowEnd={}",
                    startMillis,
                    endMillis,
                    windowStart,
                    windowEnd
            );
        }

        return adjusted;
    }

    private boolean isValidCandidateDuration(
            long startMillis,
            long endMillis
    ) {
        long durationMillis =
                endMillis
                        - startMillis;

        return durationMillis
                >= MIN_CANDIDATE_DURATION_MILLIS
                && durationMillis
                <= MAX_CANDIDATE_DURATION_MILLIS;
    }

    /*
     * JSON 우선 파싱.
     *
     * qwen이 JSON 형식을 깨거나 일반 텍스트로 시간을 반환하면
     * millisecond range fallback을 사용한다.
     */
    private List<VideoHighlightSegment> parseSegments(
            String response
    ) {
        if (!StringUtils.hasText(response)) {
            return List.of();
        }

        try {
            List<String> jsonCandidates =
                    extractJsonCandidates(
                            response
                    );

            for (String json : jsonCandidates) {
                try {
                    Object parsed =
                            objectMapper.readValue(
                                    json,
                                    Object.class
                            );

                    List<VideoHighlightSegment> segments =
                            parseSegmentValue(
                                    parsed
                            );

                    if (!segments.isEmpty()) {
                        return segments;
                    }
                } catch (Exception exception) {
                    log.debug(
                            "영상 하이라이트 JSON 후보 파싱 실패. json={}",
                            json
                    );
                }
            }
        } catch (Exception exception) {
            log.debug(
                    "영상 하이라이트 JSON 파싱 실패. timestamp fallback 사용.",
                    exception
            );
        }

        List<VideoHighlightSegment> fallbackSegments =
                extractTimestampSegments(
                        response
                );

        if (!fallbackSegments.isEmpty()) {
            log.info(
                    "영상 하이라이트 timestamp fallback 성공. candidateCount={}",
                    fallbackSegments.size()
            );
        }

        return fallbackSegments;
    }

    @SuppressWarnings("unchecked")
    private List<VideoHighlightSegment> parseSegmentValue(
            Object value
    ) {
        if (value == null) {
            return List.of();
        }

        if (value instanceof Map<?, ?> map) {
            Object wrapped =
                    map.get(
                            "highlight_segments"
                    );

            if (wrapped == null) {
                wrapped =
                        map.get(
                                "segments"
                        );
            }

            if (wrapped == null) {
                wrapped =
                        map.get(
                                "highlights"
                        );
            }

            /*
             * 실제 qwen 응답에서 확인된 wrapper.
             */
            if (wrapped == null) {
                wrapped =
                        map.get(
                                "selected_scenes"
                        );
            }

            if (wrapped == null) {
                wrapped =
                        map.get(
                                "selectedScenes"
                        );
            }

            if (wrapped == null) {
                wrapped =
                        map.get(
                                "selectedScenes"
                        );
            }

            if (wrapped != null) {
                return parseSegmentValue(
                        wrapped
                );
            }

            VideoHighlightSegment segment =
                    toHighlightSegment(
                            (Map<String, Object>) map
                    );

            return segment == null
                    ? List.of()
                    : List.of(segment);
        }

        if (value instanceof List<?> list) {
            if (isNumberPair(list)) {
                VideoHighlightSegment segment =
                        toHighlightSegment(
                                list
                        );

                return segment == null
                        ? List.of()
                        : List.of(segment);
            }

            List<VideoHighlightSegment> result =
                    new ArrayList<>();

            for (Object item : list) {
                result.addAll(
                        parseSegmentValue(
                                item
                        )
                );
            }

            return result;
        }

        return List.of();
    }

    private boolean isNumberPair(
            List<?> values
    ) {
        return values.size() >= 2
                && values.get(0) instanceof Number
                && values.get(1) instanceof Number;
    }

    private VideoHighlightSegment toHighlightSegment(
            List<?> values
    ) {
        if (!isNumberPair(values)) {
            return null;
        }

        long startMillis =
                ((Number) values.get(0))
                        .longValue();

        long endMillis =
                ((Number) values.get(1))
                        .longValue();

        if (
                startMillis < 0
                        || endMillis <= startMillis
        ) {
            return null;
        }

        return new VideoHighlightSegment(
                startMillis,
                endMillis,
                "AI selected highlight"
        );
    }

    private VideoHighlightSegment toHighlightSegment(
            Map<String, Object> item
    ) {
        Long startMillis =
                getLongValue(
                        item,
                        "startMillis",
                        "start",
                        "start_time",
                        "start_time_ms",
                        "startMs",
                        "start_ms"
                );

        Long endMillis =
                getLongValue(
                        item,
                        "endMillis",
                        "end",
                        "end_time",
                        "end_time_ms",
                        "endMs",
                        "end_ms"
                );

        if (
                startMillis == null
                        || endMillis == null
                        || startMillis < 0
                        || endMillis <= startMillis
        ) {
            return null;
        }

        String reason =
                getStringValue(
                        item,
                        "reason",
                        "description",
                        "selection_reason"
                );

        if (!StringUtils.hasText(reason)) {
            reason =
                    "AI selected highlight";
        }

        return new VideoHighlightSegment(
                startMillis,
                endMillis,
                reason
        );
    }

    private Long getLongValue(
            Map<String, Object> item,
            String... keys
    ) {
        for (String key : keys) {
            Object value =
                    item.get(
                            key
                    );

            if (value instanceof Number number) {
                return number.longValue();
            }

            if (value instanceof String text) {
                try {
                    return Long.parseLong(
                            text.trim()
                    );
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return null;
    }

    private String getStringValue(
            Map<String, Object> item,
            String... keys
    ) {
        for (String key : keys) {
            Object value =
                    item.get(
                            key
                    );

            if (value != null) {
                return value.toString();
            }
        }

        return null;
    }

    /*
     * 응답 전체에서 실제로 파싱 가능한 JSON 배열/객체 후보를 찾는다.
     *
     * 기존처럼 단순히 첫 '['부터 마지막 ']'까지 자르면
     *
     * [34:30 ~ 35:00]
     *
     * 같은 일반 텍스트를 JSON으로 오인할 수 있으므로
     * bracket depth를 이용해 각각의 JSON 후보를 분리한다.
     */
    private List<String> extractJsonCandidates(
            String response
    ) {
        if (!StringUtils.hasText(response)) {
            return List.of();
        }

        String normalized =
                response
                        .replace(
                                "```json",
                                ""
                        )
                        .replace(
                                "```JSON",
                                ""
                        )
                        .replace(
                                "```",
                                ""
                        )
                        .trim();

        List<String> result =
                new ArrayList<>();

        for (
                int index = 0;
                index < normalized.length();
                index++
        ) {
            char current =
                    normalized.charAt(
                            index
                    );

            if (
                    current != '['
                            && current != '{'
            ) {
                continue;
            }

            int end =
                    findJsonEnd(
                            normalized,
                            index
                    );

            if (end < 0) {
                continue;
            }

            result.add(
                    normalized.substring(
                            index,
                            end + 1
                    )
            );

            index =
                    end;
        }

        return result;
    }

    private int findJsonEnd(
            String text,
            int start
    ) {
        char opening =
                text.charAt(
                        start
                );

        char closing =
                opening == '['
                        ? ']'
                        : '}';

        int depth =
                0;

        boolean inString =
                false;

        boolean escaped =
                false;

        for (
                int index = start;
                index < text.length();
                index++
        ) {
            char current =
                    text.charAt(
                            index
                    );

            if (inString) {
                if (escaped) {
                    escaped =
                            false;

                    continue;
                }

                if (current == '\\') {
                    escaped =
                            true;

                    continue;
                }

                if (current == '"') {
                    inString =
                            false;
                }

                continue;
            }

            if (current == '"') {
                inString =
                        true;

                continue;
            }

            if (current == opening) {
                depth++;
            } else if (current == closing) {
                depth--;

                if (depth == 0) {
                    return index;
                }
            }
        }

        return -1;
    }

    /*
     * JSON 자체를 무시하고 응답에 포함된 실제 millisecond range를 찾는
     * 마지막 fallback.
     *
     * 예:
     *
     * [34:30 ~ 35:00] (2070000 ~ 2100000 ms)
     *
     * → 2070000 ~ 2100000
     */
    private List<VideoHighlightSegment> extractTimestampSegments(
            String response
    ) {
        if (!StringUtils.hasText(response)) {
            return List.of();
        }

        List<VideoHighlightSegment> result =
                new ArrayList<>();

        Matcher matcher =
                MILLIS_RANGE_PATTERN.matcher(
                        response
                );

        Set<String> duplicateCheck =
                new HashSet<>();

        while (matcher.find()) {
            try {
                long startMillis =
                        Long.parseLong(
                                matcher.group(1)
                        );

                long endMillis =
                        Long.parseLong(
                                matcher.group(2)
                        );

                if (
                        startMillis < 0
                                || endMillis <= startMillis
                ) {
                    continue;
                }

                String key =
                        startMillis
                                + ":"
                                + endMillis;

                if (!duplicateCheck.add(key)) {
                    continue;
                }

                result.add(
                        new VideoHighlightSegment(
                                startMillis,
                                endMillis,
                                "AI selected highlight"
                        )
                );
            } catch (NumberFormatException ignored) {
            }
        }

        return result;
    }

    private List<VideoHighlightSegment> normalizeSegments(
            List<VideoHighlightSegment> segments,
            long videoEndMillis
    ) {
        if (
                segments == null
                        || segments.isEmpty()
        ) {
            return List.of();
        }

        return segments.stream()
                .filter(segment ->
                        segment != null
                                && segment.startMillis() >= 0
                                && segment.endMillis()
                                > segment.startMillis()
                                && segment.endMillis()
                                <= videoEndMillis
                                && isValidCandidateDuration(
                                segment.startMillis(),
                                segment.endMillis()
                        )
                )
                .sorted(
                        Comparator.comparingLong(
                                VideoHighlightSegment::startMillis
                        )
                )
                .toList();
    }

    private List<VideoHighlightSegment> mergeOverlappingSegments(
            List<VideoHighlightSegment> segments
    ) {
        if (
                segments == null
                        || segments.isEmpty()
        ) {
            return List.of();
        }

        List<VideoHighlightSegment> sorted =
                segments.stream()
                        .sorted(
                                Comparator.comparingLong(
                                        VideoHighlightSegment::startMillis
                                )
                        )
                        .toList();

        List<VideoHighlightSegment> merged =
                new ArrayList<>();

        VideoHighlightSegment current =
                sorted.get(0);

        for (
                int index = 1;
                index < sorted.size();
                index++
        ) {
            VideoHighlightSegment next =
                    sorted.get(index);

            if (
                    next.startMillis()
                            <= current.endMillis()
                            + MERGE_GAP_MILLIS
            ) {
                long mergedEndMillis =
                        Math.max(
                                current.endMillis(),
                                next.endMillis()
                        );

                if (
                        mergedEndMillis
                                - current.startMillis()
                                <= MAX_CANDIDATE_DURATION_MILLIS
                ) {
                    current =
                            new VideoHighlightSegment(
                                    current.startMillis(),
                                    mergedEndMillis,
                                    mergeReasons(
                                            current.reason(),
                                            next.reason()
                                    )
                            );

                    continue;
                }
            }

            merged.add(
                    current
            );

            current =
                    next;
        }

        merged.add(
                current
        );

        return merged;
    }

    private String mergeReasons(
            String first,
            String second
    ) {
        if (!StringUtils.hasText(first)) {
            return second;
        }

        if (!StringUtils.hasText(second)) {
            return first;
        }

        if (first.equals(second)) {
            return first;
        }

        if (
                "AI selected highlight".equals(first)
                        && !"AI selected highlight".equals(second)
        ) {
            return second;
        }

        if (
                "AI selected highlight".equals(second)
        ) {
            return first;
        }

        return first
                + " / "
                + second;
    }

    private List<VideoHighlightSegment> rerankCandidates(
            List<VideoHighlightSegment> candidates,
            long videoEndMillis,
            long targetDurationMillis
    ) {
        if (
                candidates == null
                        || candidates.size() <= 1
        ) {
            return candidates == null
                    ? List.of()
                    : candidates;
        }

        Map<Integer, VideoHighlightSegment> candidateMap =
                new LinkedHashMap<>();

        StringBuilder candidateText =
                new StringBuilder();

        for (
                int index = 0;
                index < candidates.size();
                index++
        ) {
            int candidateId =
                    index + 1;

            VideoHighlightSegment candidate =
                    candidates.get(index);

            candidateMap.put(
                    candidateId,
                    candidate
            );

            candidateText.append("[candidateId=")
                    .append(
                            candidateId
                    )
                    .append("]\n")
                    .append("시간: ")
                    .append(
                            formatTime(
                                    candidate.startMillis()
                            )
                    )
                    .append(" ~ ")
                    .append(
                            formatTime(
                                    candidate.endMillis()
                            )
                    )
                    .append("\n")
                    .append("원본 위치(ms): ")
                    .append(
                            candidate.startMillis()
                    )
                    .append(" ~ ")
                    .append(
                            candidate.endMillis()
                    )
                    .append("\n")
                    .append("이유: ")
                    .append(
                            limitReason(
                                    candidate.reason()
                            )
                    )
                    .append("\n\n");
        }

        String prompt =
                """
                다음은 긴 영상 전체에서 수집한 하이라이트 후보입니다.

                영상 전체 길이:
                %s

                최종 요약 영상 목표 길이:
                약 %d초

                후보들을 전체 영상의 줄거리 중요도 순으로 평가하세요.

                가장 중요한 기준:
                1. 전체 이야기의 핵심 사건
                2. 이야기의 전환점
                3. 중요한 갈등, 결정, 폭로, 위기, 해결
                4. 인물 관계를 이해하는 데 필요한 핵심 대화
                5. 앞뒤 사건을 연결하는 중요한 원인과 결과
                6. 결말이나 핵심 결과를 이해하는 데 필요한 장면

                낮은 우선순위:
                - 단순 이동
                - 단순 인물 등장
                - 평범한 일상 장면
                - 배경이나 장소 소개
                - 로고나 물건이 보이는 장면
                - 문맥 없이 시각적으로만 눈에 띄는 장면
                - 엔딩 크레딧
                - 제작진 정보

                매우 중요:
                - 영상 앞부분에 있다는 이유로 높은 순위를 주지 마세요.
                - 영상 중반과 후반의 중요한 사건도 반드시 고려하세요.
                - 같은 사건을 보여주는 비슷한 후보들은 중복으로 높은 순위를 주지 마세요.
                - candidateId만 사용하세요.
                - 새로운 시간 구간을 만들지 마세요.

                출력은 중요도 순 candidateId JSON 배열만 반환하세요.

                예:
                [7, 15, 2, 21, 9]

                후보:
                %s
                """.formatted(
                        formatTime(
                                videoEndMillis
                        ),
                        targetDurationMillis / 1000,
                        candidateText
                );

        try {
            ChatModelResponse response =
                    chatModelProvider.chatOnce(
                            new ChatModelRequest(
                                    ChatModelType.TEXT,
                                    List.of(
                                            new ChatModelMessage(
                                                    "system",
                                                    """
                                                    당신은 영화와 드라마의 전체 줄거리 구조를 분석하여
                                                    가장 중요한 장면의 우선순위를 정하는 영상 편집자입니다.

                                                    단순히 눈에 띄는 화면보다
                                                    이야기의 원인, 전환점, 갈등, 결과를 우선하세요.
                                                    """,
                                                    null
                                            ),
                                            new ChatModelMessage(
                                                    "user",
                                                    prompt,
                                                    null
                                            )
                                    ),
                                    List.of()
                            )
                    );

            if (
                    response == null
                            || !StringUtils.hasText(
                            response.content()
                    )
            ) {
                return buildDistributedFallback(
                        candidates,
                        videoEndMillis
                );
            }

            List<Integer> rankedIds =
                    parseCandidateIds(
                            response.content()
                    );

            if (rankedIds.isEmpty()) {
                log.warn(
                        "영상 하이라이트 전체 rerank 결과 파싱 실패. fallback 사용."
                );

                return buildDistributedFallback(
                        candidates,
                        videoEndMillis
                );
            }

            List<VideoHighlightSegment> ranked =
                    new ArrayList<>();

            Set<Integer> usedIds =
                    new HashSet<>();

            for (Integer candidateId : rankedIds) {
                if (
                        candidateId == null
                                || !usedIds.add(
                                candidateId
                        )
                ) {
                    continue;
                }

                VideoHighlightSegment candidate =
                        candidateMap.get(
                                candidateId
                        );

                if (candidate != null) {
                    ranked.add(
                            candidate
                    );
                }
            }

            /*
             * 모델이 일부 ID만 반환해도
             * 나머지 후보를 fallback용으로 뒤에 보존한다.
             */
            for (
                    Map.Entry<Integer, VideoHighlightSegment> entry
                    : candidateMap.entrySet()
            ) {
                if (
                        usedIds.add(
                                entry.getKey()
                        )
                ) {
                    ranked.add(
                            entry.getValue()
                    );
                }
            }

            log.info(
                    "영상 하이라이트 전체 rerank 완료. candidateCount={}, rankedCount={}, rankedIds={}",
                    candidates.size(),
                    ranked.size(),
                    rankedIds
            );

            return ranked;
        } catch (Exception exception) {
            log.warn(
                    "영상 하이라이트 전체 rerank 실패. 시간대 분산 fallback 사용.",
                    exception
            );

            return buildDistributedFallback(
                    candidates,
                    videoEndMillis
            );
        }
    }

    private List<Integer> parseCandidateIds(
            String response
    ) {
        if (!StringUtils.hasText(response)) {
            return List.of();
        }

        try {
            List<String> jsonCandidates =
                    extractJsonCandidates(
                            response
                    );

            for (String json : jsonCandidates) {
                try {
                    Object parsed =
                            objectMapper.readValue(
                                    json,
                                    Object.class
                            );

                    List<Integer> result =
                            new ArrayList<>();

                    collectCandidateIds(
                            parsed,
                            result
                    );

                    if (!result.isEmpty()) {
                        return result;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        /*
         * rerank도 qwen이
         *
         * 7, 15, 2, 21
         *
         * 처럼 배열 없이 반환할 가능성이 있으므로 fallback.
         */
        List<Integer> result =
                new ArrayList<>();

        Matcher matcher =
                Pattern.compile(
                                "\\b(\\d+)\\b"
                        )
                        .matcher(
                                response
                        );

        while (matcher.find()) {
            try {
                result.add(
                        Integer.parseInt(
                                matcher.group(1)
                        )
                );
            } catch (NumberFormatException ignored) {
            }
        }

        return result;
    }

    private void collectCandidateIds(
            Object value,
            List<Integer> result
    ) {
        if (value == null) {
            return;
        }

        if (value instanceof Number number) {
            result.add(
                    number.intValue()
            );

            return;
        }

        if (value instanceof String text) {
            try {
                result.add(
                        Integer.parseInt(
                                text.trim()
                        )
                );
            } catch (NumberFormatException ignored) {
            }

            return;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                collectCandidateIds(
                        item,
                        result
                );
            }

            return;
        }

        if (value instanceof Map<?, ?> map) {
            Object candidateId =
                    map.get(
                            "candidateId"
                    );

            if (candidateId == null) {
                candidateId =
                        map.get(
                                "id"
                        );
            }

            if (candidateId != null) {
                collectCandidateIds(
                        candidateId,
                        result
                );

                return;
            }

            Object ranked =
                    map.get(
                            "rankedCandidates"
                    );

            if (ranked == null) {
                ranked =
                        map.get(
                                "candidates"
                        );
            }

            if (ranked == null) {
                ranked =
                        map.get(
                                "ranking"
                        );
            }

            if (ranked != null) {
                collectCandidateIds(
                        ranked,
                        result
                );
            }
        }
    }

    private List<VideoHighlightSegment> buildDistributedFallback(
            List<VideoHighlightSegment> candidates,
            long videoEndMillis
    ) {
        if (
                candidates == null
                        || candidates.isEmpty()
        ) {
            return List.of();
        }

        int bucketCount =
                Math.min(
                        5,
                        candidates.size()
                );

        List<List<VideoHighlightSegment>> buckets =
                new ArrayList<>();

        for (
                int index = 0;
                index < bucketCount;
                index++
        ) {
            buckets.add(
                    new ArrayList<>()
            );
        }

        for (VideoHighlightSegment candidate : candidates) {
            int bucketIndex =
                    resolveBucketIndex(
                            candidate.startMillis(),
                            videoEndMillis,
                            bucketCount
                    );

            buckets.get(
                    bucketIndex
            ).add(
                    candidate
            );
        }

        List<VideoHighlightSegment> distributed =
                new ArrayList<>();

        int position =
                0;

        boolean added;

        do {
            added =
                    false;

            for (
                    List<VideoHighlightSegment> bucket
                    : buckets
            ) {
                if (position < bucket.size()) {
                    distributed.add(
                            bucket.get(
                                    position
                            )
                    );

                    added =
                            true;
                }
            }

            position++;
        } while (added);

        return distributed;
    }

    private int resolveBucketIndex(
            long startMillis,
            long videoEndMillis,
            int bucketCount
    ) {
        if (
                videoEndMillis <= 0
                        || bucketCount <= 1
        ) {
            return 0;
        }

        double ratio =
                (double) startMillis
                        / (double) videoEndMillis;

        int index =
                (int) (
                        ratio
                                * bucketCount
                );

        return Math.max(
                0,
                Math.min(
                        bucketCount - 1,
                        index
                )
        );
    }

    private List<VideoHighlightSegment> selectDistributedSegments(
            List<VideoHighlightSegment> rankedCandidates,
            long videoEndMillis,
            long targetDurationMillis
    ) {
        if (
                rankedCandidates == null
                        || rankedCandidates.isEmpty()
                        || targetDurationMillis <= 0
        ) {
            return List.of();
        }

        int preferredBucketCount =
                (int) Math.max(
                        3,
                        Math.min(
                                6,
                                targetDurationMillis
                                        / 60_000L
                        )
                );

        preferredBucketCount =
                Math.min(
                        preferredBucketCount,
                        rankedCandidates.size()
                );

        Map<Integer, List<VideoHighlightSegment>> bucketCandidates =
                new HashMap<>();

        /*
         * rankedCandidates 자체가 중요도 순서이므로
         * 각 bucket 내부에서도 먼저 들어간 후보가 더 중요하다.
         */
        for (VideoHighlightSegment candidate : rankedCandidates) {
            int bucketIndex =
                    resolveBucketIndex(
                            candidate.startMillis(),
                            videoEndMillis,
                            preferredBucketCount
                    );

            bucketCandidates
                    .computeIfAbsent(
                            bucketIndex,
                            key ->
                                    new ArrayList<>()
                    )
                    .add(
                            candidate
                    );
        }

        List<VideoHighlightSegment> selected =
                new ArrayList<>();

        long selectedDurationMillis =
                0L;

        /*
         * 1차:
         * 영상의 각 시간대에서 가장 중요한 후보를 하나씩 확보한다.
         */
        for (
                int bucketIndex = 0;
                bucketIndex < preferredBucketCount;
                bucketIndex++
        ) {
            List<VideoHighlightSegment> bucket =
                    bucketCandidates.get(
                            bucketIndex
                    );

            if (
                    bucket == null
                            || bucket.isEmpty()
            ) {
                continue;
            }

            VideoHighlightSegment candidate =
                    bucket.get(0);

            if (
                    hasMeaningfulOverlap(
                            selected,
                            candidate
                    )
            ) {
                continue;
            }

            long remainingMillis =
                    targetDurationMillis
                            - selectedDurationMillis;

            if (remainingMillis <= 0) {
                break;
            }

            VideoHighlightSegment fitted =
                    fitSegmentToRemainingDuration(
                            candidate,
                            remainingMillis
                    );

            if (fitted != null) {
                selected.add(
                        fitted
                );

                selectedDurationMillis +=
                        fitted.endMillis()
                                - fitted.startMillis();
            }
        }

        /*
         * 2차:
         * 아직 시간이 부족하면 전체 중요도 순서대로 채운다.
         */
        for (VideoHighlightSegment candidate : rankedCandidates) {
            if (
                    selectedDurationMillis
                            >= targetDurationMillis
            ) {
                break;
            }

            if (
                    containsSameSegment(
                            selected,
                            candidate
                    )
                            || hasMeaningfulOverlap(
                            selected,
                            candidate
                    )
            ) {
                continue;
            }

            long remainingMillis =
                    targetDurationMillis
                            - selectedDurationMillis;

            VideoHighlightSegment fitted =
                    fitSegmentToRemainingDuration(
                            candidate,
                            remainingMillis
                    );

            if (fitted == null) {
                continue;
            }

            selected.add(
                    fitted
            );

            selectedDurationMillis +=
                    fitted.endMillis()
                            - fitted.startMillis();
        }

        log.info(
                "영상 하이라이트 목표 길이 조정 완료. candidateCount={}, selectedCount={}, targetDurationMillis={}, selectedDurationMillis={}",
                rankedCandidates.size(),
                selected.size(),
                targetDurationMillis,
                selectedDurationMillis
        );

        if (
                selectedDurationMillis
                        < targetDurationMillis
        ) {
            log.warn(
                    "영상 하이라이트 후보 총 길이가 목표 길이보다 짧습니다. targetDurationMillis={}, selectedDurationMillis={}, shortageMillis={}",
                    targetDurationMillis,
                    selectedDurationMillis,
                    targetDurationMillis
                            - selectedDurationMillis
            );
        }

        return selected;
    }

    private VideoHighlightSegment fitSegmentToRemainingDuration(
            VideoHighlightSegment candidate,
            long remainingMillis
    ) {
        if (
                candidate == null
                        || remainingMillis <= 0
        ) {
            return null;
        }

        long durationMillis =
                candidate.endMillis()
                        - candidate.startMillis();

        if (durationMillis <= 0) {
            return null;
        }

        if (durationMillis <= remainingMillis) {
            return candidate;
        }

        if (
                remainingMillis
                        < MIN_PARTIAL_SEGMENT_MILLIS
        ) {
            return null;
        }

        return new VideoHighlightSegment(
                candidate.startMillis(),
                candidate.startMillis()
                        + remainingMillis,
                candidate.reason()
        );
    }

    private boolean containsSameSegment(
            List<VideoHighlightSegment> selected,
            VideoHighlightSegment candidate
    ) {
        return selected.stream()
                .anyMatch(segment ->
                        segment.startMillis()
                                == candidate.startMillis()
                                && segment.endMillis()
                                == candidate.endMillis()
                );
    }

    private boolean hasMeaningfulOverlap(
            List<VideoHighlightSegment> selected,
            VideoHighlightSegment candidate
    ) {
        for (VideoHighlightSegment segment : selected) {
            long overlapStart =
                    Math.max(
                            segment.startMillis(),
                            candidate.startMillis()
                    );

            long overlapEnd =
                    Math.min(
                            segment.endMillis(),
                            candidate.endMillis()
                    );

            long overlapMillis =
                    overlapEnd
                            - overlapStart;

            if (overlapMillis <= 0) {
                continue;
            }

            long candidateDuration =
                    candidate.endMillis()
                            - candidate.startMillis();

            long selectedDuration =
                    segment.endMillis()
                            - segment.startMillis();

            long shorterDuration =
                    Math.min(
                            candidateDuration,
                            selectedDuration
                    );

            if (
                    shorterDuration > 0
                            && overlapMillis
                            >= shorterDuration * 0.5
            ) {
                return true;
            }
        }

        return false;
    }

    private long calculateTotalDuration(
            List<VideoHighlightSegment> segments
    ) {
        if (
                segments == null
                        || segments.isEmpty()
        ) {
            return 0L;
        }

        return segments.stream()
                .mapToLong(segment ->
                        segment.endMillis()
                                - segment.startMillis()
                )
                .sum();
    }

    private String buildContext(
            List<ChatFileChunk> chunks
    ) {
        if (
                chunks == null
                        || chunks.isEmpty()
        ) {
            return "";
        }

        StringBuilder context =
                new StringBuilder();

        for (ChatFileChunk chunk : chunks) {
            context.append("[")
                    .append(
                            formatTime(
                                    chunk.getStartMillis()
                            )
                    )
                    .append(" ~ ")
                    .append(
                            formatTime(
                                    chunk.getEndMillis()
                            )
                    )
                    .append("] ");

            if (isVisionChunk(chunk)) {
                context.append(
                        "[VISION]"
                );
            } else {
                context.append(
                        "[TRANSCRIPT]"
                );
            }

            context.append("\n")
                    .append(
                            limitText(
                                    normalizeContent(
                                            chunk.getContent()
                                    )
                            )
                    )
                    .append("\n\n");
        }

        return context.toString();
    }

    private boolean isValidVideoChunk(
            ChatFileChunk chunk
    ) {
        return chunk != null
                && chunk.getStartMillis() != null
                && chunk.getEndMillis() != null
                && StringUtils.hasText(
                chunk.getContent()
        );
    }

    private boolean isVisionChunk(
            ChatFileChunk chunk
    ) {
        return chunk.getContent()
                .startsWith(
                        VISION_PREFIX
                );
    }

    private String normalizeContent(
            String content
    ) {
        if (!StringUtils.hasText(content)) {
            return "";
        }

        if (
                content.startsWith(
                        VISION_PREFIX
                )
        ) {
            return content.substring(
                            VISION_PREFIX.length()
                    )
                    .trim();
        }

        return content.trim();
    }

    private String limitText(
            String text
    ) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        if (
                text.length()
                        <= MAX_CHUNK_CONTENT_LENGTH
        ) {
            return text;
        }

        return text.substring(
                0,
                MAX_CHUNK_CONTENT_LENGTH
        ) + "...";
    }

    private String limitReason(
            String reason
    ) {
        if (!StringUtils.hasText(reason)) {
            return "이유 없음";
        }

        if (
                reason.length()
                        <= MAX_REASON_LENGTH
        ) {
            return reason;
        }

        return reason.substring(
                0,
                MAX_REASON_LENGTH
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
                (totalSeconds % 3600)
                        / 60;

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

    private record WindowCandidateResult(
            long windowStart,
            long windowEnd,
            List<VideoHighlightSegment> candidates
    ) {
    }
}