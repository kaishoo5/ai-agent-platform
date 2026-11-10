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

    // Gemini 구간 분석 결과의 장면별 시간 표기 (HH:mm:ss 또는 mm:ss).
    private static final Pattern GEMINI_SCENE_TIME_PATTERN =
            Pattern.compile("(?<![0-9])(?:\\*\\*)?(\\d{1,2}:\\d{2}(?::\\d{2})?)(?:\\*\\*)?(?![0-9:])");

    private static final int MAX_GEMINI_SCENE_LINES_PER_WINDOW = 100;
    private static final int MAX_GEMINI_SCENE_LINE_LENGTH = 260;


    private static final long WINDOW_DURATION_MILLIS =
            10 * 60 * 1000L;

    private static final int MAX_CHUNK_CONTENT_LENGTH =
            700;

    private static final long MIN_CANDIDATE_DURATION_MILLIS =
            15_000L;

    private static final long MAX_CANDIDATE_DURATION_MILLIS =
            60_000L;

    // Gemini가 60초를 조금 넘긴 후보를 반환하면 자막/장면 경계로 재조정한다.
    private static final long MAX_REPAIRABLE_CANDIDATE_DURATION_MILLIS =
            90_000L;

    private static final long MIN_REPAIRED_CANDIDATE_DURATION_MILLIS =
            45_000L;

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

    private static final int MAX_CACHED_VIDEOS = 16;
    public final ChatFileChunkRepository chatFileChunkRepository;
    private final Map<String, CandidateCacheEntry> candidateCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CandidateCacheEntry> eldest) {
                    return size() > MAX_CACHED_VIDEOS;
                }
            };
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
                        chunks,
                        SelectionPurpose.SUMMARY
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

    public synchronized List<VideoHighlightSegment> selectCandidates(
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
            candidateCache.remove(fileId);
            return List.of();
        }

        // 청크 내용이 바뀌면 기존 캐시를 사용하지 않는다.
        int fingerprint = 1;
        for (ChatFileChunk chunk : chunks) {
            fingerprint = 31 * fingerprint + Objects.hash(
                    chunk.getChunkIndex(), chunk.getStartMillis(),
                    chunk.getEndMillis(), chunk.getContent()
            );
        }
        CandidateCacheEntry cached = candidateCache.get(fileId);
        if (cached != null && cached.fingerprint() == fingerprint) {
            log.info("영상 하이라이트 후보 캐시 사용. fileId={}, candidateCount={}",
                    fileId, cached.candidates().size());
            return cached.candidates();
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
                        chunks,
                        SelectionPurpose.SHORTS
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

        List<VideoHighlightSegment> result = List.copyOf(mergedCandidates);
        candidateCache.put(fileId, new CandidateCacheEntry(fingerprint, result));
        log.info("영상 하이라이트 후보 캐시 저장. fileId={}, candidateCount={}",
                fileId, result.size());
        return result;
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
            List<ChatFileChunk> chunks,
            SelectionPurpose purpose
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
                                                currentWindowEnd,
                                                purpose
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
            long windowEnd,
            SelectionPurpose purpose
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
                            windowEnd,
                            purpose
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
            long windowEnd,
            SelectionPurpose purpose
    ) {
        // 모델에는 window 기준 상대 밀리초를 전달한다.
        // 절대 timestamp를 생성하면서 자릿수나 범위를 잘못 해석하는 문제를 줄인다.
        String context =
                buildWindowContext(
                        chunks,
                        windowStart,
                        windowEnd
                );

        long windowDurationMillis = windowEnd - windowStart;

        String userPrompt = purpose == SelectionPurpose.SUMMARY
                ? """
                다음은 긴 영상의 한 구간에서 추출한 자막과 화면 분석입니다.
                전체 영상을 하나의 요약 MP4로 편집할 때 필요한 핵심 장면 후보를 찾으세요.
                시청자의 관심을 끄는 짧은 쇼츠가 아니라, 영상 전체의 내용과 흐름을
                정확하게 이해할 수 있도록 대표 장면을 선정하는 작업입니다.

                후보 평가 기준 (중요도 순):
                1. 핵심 사실, 주요 사건, 결정, 중요한 발언과 결과
                2. 사건의 원인과 결과, 앞뒤 맥락을 이해하는 데 필요한 장면
                3. 이야기의 전환점과 의미 있는 변화
                4. 영상 전체를 대표하는 정보와 주요 인물의 입장
                5. 시간순으로 이어 붙였을 때 내용이 자연스럽게 전달되는가

                단순한 자극, 반응, 웃음, 쇼츠용 후킹만으로 후보를 선정하지 마세요.
                광고, 반복 설명, 무의미한 장면, 오프닝과 엔딩 크레딧은 제외하세요.
                같은 사건을 반복 설명하는 구간은 대표 장면만 선택하세요.
                실제 데이터에 없는 사건, 발언, 시간은 추측하지 마세요.

                시간 규칙 (매우 중요):
                - 아래 [startMillis,endMillis]는 이 구간의 시작을 0으로 둔 상대 밀리초입니다.
                - 출력도 반드시 상대 밀리초를 사용하세요. 원본 영상의 절대 시간을 출력하지 마세요.
                - 이 window의 원본 절대 시작 시각은 %dms입니다.
                - 예: [SCENE_AT 111000ms]라면 startMillis는 111000 부근이어야 합니다.
                  111000에 원본 절대 시작 시각을 다시 더하지 마세요.
                - [SCENE_AT]에 제시되지 않은 시각으로 이동해 후보를 만들지 마세요.
                - 모든 startMillis/endMillis는 0 이상 %d 이하여야 합니다.
                - endMillis는 startMillis보다 커야 합니다.
                - 각 장면은 15,000~60,000ms 길이로 선택하세요.
                - 중요한 장면을 최대 4개까지 선택하고, 없으면 []을 반환하세요.
                - [SCENE_AT Nms]는 장면 시작 시각이지 종료 시각이 아닙니다.
                - 10분짜리 분석 청크 전체를 하나의 장면으로 취급하지 마세요.
                - 시간은 대략적인 편집 후보이며 실제 컷 경계 검증이 필요합니다.

                reason에는 해당 장면이 전체 요약에 필요한 이유를 구체적으로 작성하세요.
                suggestedTitle에는 해당 장면의 핵심 사건을 짧게 적거나 빈 문자열을 사용하세요.
                JSON 배열만 반환하세요. 예시:
                [{"startMillis":15000,"endMillis":45000,"reason":"사건의 원인을 설명하는 핵심 발언","suggestedTitle":"사건의 원인"}]

                영상 분석 데이터 (각 줄의 숫자는 상대 밀리초):
                %s
                """.formatted(windowStart, windowDurationMillis, context)
                : """
                다음은 긴 영상의 한 구간에서 추출한 자막과 화면 분석입니다.
                이 구간에서 30~60초짜리 쇼츠로 독립 편집할 가치가 있는 장면을 찾으세요.
                줄거리상 중요하더라도 맥락 없이는 이해하기 어려운 장면보다,
                짧은 영상만 봐도 재미와 사건의 의미가 전달되는 장면을 우선하세요.

                후보 평가 기준 (중요도 순):
                1. 시작 3초: 첫 대사나 반응부터 시청자의 궁금증을 유발하는가?
                2. 독립적 이해: 긴 영상의 앞뒤 내용을 몰라도 상황을 이해할 수 있는가?
                3. 재미와 몰입: 웃음, 의외의 반전, 놀람, 긴장, 갈등, 솔직한 고백 등
                   명확한 감정 반응이나 흥미로운 사건이 있는가?
                4. 완결성: 한 가지 사건이나 대화가 짧은 구간 안에서 전개되고
                   의미 있는 반응 또는 마무리까지 이어지는가?
                5. 편집 적합성: 단순 설명, 긴 도입, 반복, 무의미한 이동이 적은가?
                억지로 재미를 만들어내지 말고 입력 자료에서 확인되는 근거만 사용하세요.
                쇼츠로 독립 편집하기 어려운 장면은 후보로 내지 마세요.

                시간 규칙 (매우 중요):
                - 아래 [startMillis,endMillis]는 이 구간의 시작을 0으로 둔 상대 밀리초입니다.
                - 출력도 반드시 상대 밀리초를 사용하세요. 원본 영상의 절대 시간을 출력하지 마세요.
                - 이 window의 원본 절대 시작 시각은 %dms입니다.
                - 예: [SCENE_AT 111000ms]라면 startMillis는 111000 부근이어야 합니다.
                  111000에 원본 절대 시작 시각을 다시 더하지 마세요.
                - [SCENE_AT]에 제시되지 않은 시각으로 이동해 후보를 만들지 마세요.
                - 모든 startMillis/endMillis는 0 이상 %d 이하여야 합니다.
                - endMillis는 startMillis보다 커야 합니다.
                - 각 장면은 15,000~60,000ms 길이로 선택하세요.
                - 가능한 경우 2~4개만 선택하고, 중요한 장면이 없으면 []을 반환하세요.
                - 입력에 없는 사건이나 시간을 만들어내지 마세요.
                - [SCENE_AT Nms]는 Gemini가 기록한 장면 시작 시각입니다.
                  장면의 종료 시각이 아니므로 인접 장면과 대화 흐름을 참고하세요.
                - [SCENE_AT]가 있는 구간에서는 실제 기록된 장면 시각에
                  근거해 후보를 선정하고, 10분 청크 전체를 한 장면으로 취급하지 마세요.
                - 시간은 대략적인 편집 후보이며 실제 컷 경계 검증이 필요합니다.

                각 후보에 suggestedTitle도 반드시 작성하세요.
                - suggestedTitle은 영상의 실제 사건을 표현하는 한국어 쇼츠 제목입니다.
                - 24자 이내의 자연스럽고 완결된 제목으로 작성하세요.
                - 인물·사건·반응 중 실제로 확인되는 핵심을 구체적으로 드러내세요.
                - 짧은 영상만 봐도 무엇에 관한 장면인지 알 수 있게 작성하세요.
                - 낚시성 과장, 허위 결과, 확인되지 않은 감정이나 사실은 넣지 마세요.
                - reason을 그대로 복사하거나 문장 중간을 잘라 쓰지 마세요.
                - 제공되지 않은 인물, 결과, 사건을 지어내지 마세요.
                - 제목을 만들기 어려우면 빈 문자열로 반환하세요.

                JSON 배열만 반환하세요. 예시:
                [{"startMillis":15000,"endMillis":45000,"reason":"핵심 사건을 설명하는 대화","suggestedTitle":"핵심 사건의 진실이 밝혀진 순간"}]

                영상 분석 데이터 (각 줄의 숫자는 상대 밀리초):
                %s
                """.formatted(
                windowStart,
                windowDurationMillis,
                context
        );
        ChatModelResponse response =
                chatModelProvider.chatOnce(
                        new ChatModelRequest(
                                ChatModelType.TEXT,
                                List.of(
                                        new ChatModelMessage(
                                                "system",
                                                purpose == SelectionPurpose.SUMMARY
                                                        ? """
                                                당신은 긴 영상의 핵심 내용을 충실하게 전달하는
                                                요약 영상 전문 편집자입니다.
                                                사건의 중요도, 원인과 결과, 시간적 흐름,
                                                대표성, 정보의 정확성을 기준으로 장면을 선택하세요.
                                                쇼츠용 자극이나 반응보다 전체 내용의 이해를 우선하세요.
                                                제공되지 않은 내용을 추측하지 마세요.
                                                """
                                                        : """
                                                당신은 영화, 드라마, 다큐멘터리 등
                                                긴 영상에서 독립적인 쇼츠로 재미있게 볼 수 있는
                                                장면을 고르는 전문 숏폼 영상 편집자입니다.

                                                줄거리의 중요도만이 아니라 초반 흡입력,
                                                맥락 없이 이해되는지, 감정 반응과 짧은 구간의
                                                완결성을 중점 평가하세요.

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

        List<VideoHighlightSegment> relativeCandidates =
                parseSegments(response.content());

        List<VideoHighlightSegment> absoluteCandidates =
                new ArrayList<>();

        for (VideoHighlightSegment candidate : relativeCandidates) {
            if (candidate == null) {
                continue;
            }

            long rawStart = candidate.startMillis();
            long rawEnd = candidate.endMillis();
            long relativeStart;
            long relativeEnd;

            // 정상 상대 시각을 우선한다. windowStart=0인 경우에도 동일하게 동작한다.
            if (rawStart >= 0 && rawEnd > rawStart
                    && rawEnd <= windowDurationMillis) {
                relativeStart = rawStart;
                relativeEnd = rawEnd;
            } else if (rawStart >= windowStart && rawEnd > rawStart
                    && rawEnd <= windowEnd) {
                // 모델이 window 내 절대 밀리초를 반환했을 때만 안전하게 변환한다.
                relativeStart = rawStart - windowStart;
                relativeEnd = rawEnd - windowStart;
                log.info(
                        "영상 하이라이트 절대 시각을 상대 시각으로 보정. rawStart={}, rawEnd={}, windowStart={}",
                        rawStart, rawEnd, windowStart
                );
            } else {
                // 다른 window의 절대 시각은 현재 구간으로 억지 이동시키지 않는다.
                log.warn(
                        "영상 하이라이트 window 밖 후보 제외. rawStart={}, rawEnd={}, windowStart={}, windowEnd={}",
                        rawStart, rawEnd, windowStart, windowEnd
                );
                continue;
            }

            long absoluteStart = windowStart + relativeStart;
            long absoluteEnd = windowStart + relativeEnd;

            if (absoluteEnd - absoluteStart > MAX_CANDIDATE_DURATION_MILLIS) {
                VideoHighlightSegment repaired = repairOversizedCandidate(
                        absoluteStart,
                        absoluteEnd,
                        candidate,
                        chunks
                );
                if (repaired != null) {
                    absoluteStart = repaired.startMillis();
                    absoluteEnd = repaired.endMillis();
                }
            }

            if (!isValidCandidateDuration(absoluteStart, absoluteEnd)) {
                log.warn(
                        "영상 하이라이트 후보 길이 오류. relativeStart={}, relativeEnd={}, absoluteStart={}, absoluteEnd={}, windowStart={}, windowEnd={}",
                        relativeStart, relativeEnd, absoluteStart, absoluteEnd, windowStart, windowEnd
                );
                continue;
            }

            log.debug(
                    "영상 하이라이트 시간 변환. windowStart={}, windowEnd={}, relativeStart={}, relativeEnd={}, absoluteStart={}, absoluteEnd={}",
                    windowStart,
                    windowEnd,
                    relativeStart,
                    relativeEnd,
                    absoluteStart,
                    absoluteEnd
            );

            absoluteCandidates.add(
                    new VideoHighlightSegment(
                            absoluteStart,
                            absoluteEnd,
                            candidate.reason(),
                            candidate.suggestedTitle()
                    )
            );
        }

        return absoluteCandidates;
    }

    /**
     * 60초 초과 후보는 자막/장면 청크 경계 중 가장 자연스러운 45~60초 구간으로 보정한다.
     * 원본 영상의 실제 문장 완결성은 이 단계에서 보장할 수 없으므로,
     * 경계를 찾지 못하면 임의로 60초를 잘라내지 않고 기존 검증에서 제외한다.
     */
    private VideoHighlightSegment repairOversizedCandidate(
            long originalStart,
            long originalEnd,
            VideoHighlightSegment candidate,
            List<ChatFileChunk> chunks
    ) {
        long originalDuration = originalEnd - originalStart;
        if (originalDuration <= MAX_CANDIDATE_DURATION_MILLIS
                || originalDuration > MAX_REPAIRABLE_CANDIDATE_DURATION_MILLIS
                || chunks == null || chunks.isEmpty()) {
            return null;
        }

        TreeSet<Long> boundaries = new TreeSet<>();
        boundaries.add(originalStart);
        boundaries.add(originalEnd);
        for (ChatFileChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            long start = chunk.getStartMillis();
            long end = chunk.getEndMillis();
            if (start > originalStart && start < originalEnd) {
                boundaries.add(start);
            }
            if (end > originalStart && end < originalEnd) {
                boundaries.add(end);
            }

            // Gemini 장면 분석은 긴 청크의 본문에 개별 장면 시각을 기록한다.
            // 청크 자체의 경계만 사용하면 60~90초 후보 안에 경계가 없어
            // 보정이 불가능하므로 본문에 기록된 실제 장면 시작 시각도 사용한다.
            if (isGeminiSegmentChunk(chunk)) {
                for (String line : chunk.getContent().split("\\R")) {
                    Matcher matcher = GEMINI_SCENE_TIME_PATTERN.matcher(line);
                    if (!matcher.find()) {
                        continue;
                    }
                    long sceneMillis = parseSceneTimeMillis(matcher.group(1));
                    if (sceneMillis > originalStart && sceneMillis < originalEnd
                            && sceneMillis >= chunk.getStartMillis()
                            && sceneMillis < chunk.getEndMillis()) {
                        boundaries.add(sceneMillis);
                    }
                }
            }
        }

        long bestStart = -1;
        long bestEnd = -1;
        long bestScore = Long.MAX_VALUE;
        for (long start : boundaries) {
            for (long end : boundaries.tailSet(start, false)) {
                long duration = end - start;
                if (duration < MIN_REPAIRED_CANDIDATE_DURATION_MILLIS) {
                    continue;
                }
                if (duration > MAX_CANDIDATE_DURATION_MILLIS) {
                    break;
                }
                // 긴 장면을 양쪽에서 조금씩 자르기보다 가능한 한
                // 원래 시작/종료 중 하나를 유지하고 60초에 가깝게 선정한다.
                long score = (MAX_CANDIDATE_DURATION_MILLIS - duration) * 3
                        + Math.min(start - originalStart, originalEnd - end);
                if (score < bestScore) {
                    bestScore = score;
                    bestStart = start;
                    bestEnd = end;
                }
            }
        }

        if (bestStart < 0) {
            log.warn(
                    "영상 하이라이트 초과 후보 경계 보정 실패. startMillis={}, endMillis={}, durationMillis={}, boundaryCount={}, boundaries={}",
                    originalStart, originalEnd, originalDuration, boundaries.size(), boundaries
            );
            return null;
        }

        log.info(
                "영상 하이라이트 초과 후보 경계 보정. originalStart={}, originalEnd={}, adjustedStart={}, adjustedEnd={}, originalDuration={}, adjustedDuration={}",
                originalStart, originalEnd, bestStart, bestEnd,
                originalDuration, bestEnd - bestStart
        );
        return new VideoHighlightSegment(
                bestStart,
                bestEnd,
                candidate.reason(),
                candidate.suggestedTitle()
        );
    }

    private String buildWindowContext(
            List<ChatFileChunk> chunks,
            long windowStart,
            long windowEnd
    ) {
        StringBuilder context = new StringBuilder();

        for (ChatFileChunk chunk : chunks) {
            long start = Math.max(windowStart, chunk.getStartMillis()) - windowStart;
            long end = Math.min(windowEnd, chunk.getEndMillis()) - windowStart;

            if (end <= start) {
                continue;
            }

            if (isGeminiSegmentChunk(chunk)) {
                appendGeminiSceneContext(context, chunk, windowStart, windowEnd);
                continue;
            }

            context.append('[')
                    .append(start)
                    .append(',')
                    .append(end)
                    .append("] ")
                    .append(isVisionChunk(chunk) ? "[VISION] " : "[TRANSCRIPT] ")
                    .append(limitText(normalizeContent(chunk.getContent())))
                    .append('\n');
        }

        return context.toString();
    }

    /**
     * Gemini는 10분 구간 하나를 하나의 청크로 저장한다. 본문에 기록된
     * 장면 시각을 사용하고, 10분짜리 청크 전체를 하나의 장면으로 취급하지 않는다.
     * 타임스탬프는 시작 지점에 대한 단서일 뿐 정확한 편집 종료 시각은 아니다.
     */
    private boolean isGeminiSegmentChunk(ChatFileChunk chunk) {
        return chunk.getEndMillis() - chunk.getStartMillis() >= 120_000L
                && !isVisionChunk(chunk)
                && GEMINI_SCENE_TIME_PATTERN.matcher(chunk.getContent()).find();
    }

    private void appendGeminiSceneContext(
            StringBuilder context,
            ChatFileChunk chunk,
            long windowStart,
            long windowEnd
    ) {
        int count = 0;
        for (String line : chunk.getContent().split("\\R")) {
            Matcher matcher = GEMINI_SCENE_TIME_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            long absoluteMillis = parseSceneTimeMillis(matcher.group(1));
            if (absoluteMillis < Math.max(windowStart, chunk.getStartMillis())
                    || absoluteMillis >= Math.min(windowEnd, chunk.getEndMillis())) {
                continue;
            }

            long relativeMillis = absoluteMillis - windowStart;
            String description = line.trim();
            if (description.length() > MAX_GEMINI_SCENE_LINE_LENGTH) {
                description = description.substring(0, MAX_GEMINI_SCENE_LINE_LENGTH);
            }

            context.append("[SCENE_AT ")
                    .append(relativeMillis)
                    .append("ms] ")
                    .append(description)
                    .append('\n');

            if (++count >= MAX_GEMINI_SCENE_LINES_PER_WINDOW) {
                break;
            }
        }

        if (count == 0) {
            context.append("[구간 내 유효한 장면 시각 없음: 후보 추출 불가]\n");
        }
    }

    private long parseSceneTimeMillis(String time) {
        String[] parts = time.split(":");
        try {
            long seconds = parts.length == 3
                    ? Long.parseLong(parts[0]) * 3600L
                    + Long.parseLong(parts[1]) * 60L
                    + Long.parseLong(parts[2])
                    : Long.parseLong(parts[0]) * 60L
                    + Long.parseLong(parts[1]);
            return seconds * 1000L;
        } catch (NumberFormatException exception) {
            return -1L;
        }
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
                                    candidate.reason(),
                                    candidate.suggestedTitle()
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
                reason,
                getStringValue(item, "suggestedTitle", "suggested_title")
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
                                    ),
                                    current.suggestedTitle() != null && !current.suggestedTitle().isBlank()
                                            ? current.suggestedTitle() : next.suggestedTitle()
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
                    .append("\n추천 제목: ")
                    .append(
                            StringUtils.hasText(candidate.suggestedTitle())
                                    ? candidate.suggestedTitle()
                                    : "없음"
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
                candidate.reason(),
                candidate.suggestedTitle()
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

    private enum SelectionPurpose { SUMMARY, SHORTS }

    private record CandidateCacheEntry(int fingerprint, List<VideoHighlightSegment> candidates) {
    }

    private record WindowCandidateResult(
            long windowStart,
            long windowEnd,
            List<VideoHighlightSegment> candidates
    ) {
    }
}