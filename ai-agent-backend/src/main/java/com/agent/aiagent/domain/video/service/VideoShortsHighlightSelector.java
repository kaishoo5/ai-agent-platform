package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoShortsHighlightSelector {

    // 한국어 형태소 분석기 없이도 반복되는 고유명사(예: 아담, 조성모, 노을)를
    // 감지할 수 있도록 주요 단어를 비교한다. 일반적인 영상 설명 단어는 제외한다.
    private static final Set<String> GENERIC_TOPIC_WORDS = Set.of(
            "영상", "쇼츠", "구간", "장면", "내용", "소개", "이야기", "사연", "주제",
            "핵심", "대화", "발표", "과정", "당시", "실제", "이유", "공개", "폭로",
            "가수", "노래", "음악", "무대", "방송", "제작", "비용", "출연", "사건",
            "반응", "상황", "시절", "관련", "대한", "통해", "때문", "정도", "진짜",
            "그리고", "하지만", "어떻게", "이렇게", "저렇게", "사람", "모습", "설명",
            "탄생", "비화", "진행", "최초", "최고", "과거", "현재", "시작", "마지막"
    );
    private final VideoHighlightSelector videoHighlightSelector;
    private final ChatFileChunkRepository chatFileChunkRepository;
    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;

    public VideoHighlightSegment select(
            String fileId,
            long targetDurationMillis
    ) {
        return select(
                fileId,
                targetDurationMillis,
                List.of()
        );
    }

    public VideoHighlightSegment select(
            String fileId,
            long targetDurationMillis,
            List<VideoHighlightSegment> excludeSegments
    ) {
        List<VideoHighlightSegment> candidates =
                selectCandidates(
                        fileId
                );

        return selectFromCandidates(
                fileId,
                targetDurationMillis,
                candidates,
                excludeSegments
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

        return videoHighlightSelector.selectCandidates(
                fileId
        );
    }

    public VideoHighlightSegment selectFromCandidates(
            String fileId,
            long targetDurationMillis,
            List<VideoHighlightSegment> candidates,
            List<VideoHighlightSegment> excludeSegments
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

        if (
                candidates == null
                        || candidates.isEmpty()
        ) {
            throw new IllegalStateException(
                    "쇼츠로 사용할 영상 후보 구간이 없습니다."
            );
        }

        long videoEndMillis = chatFileChunkRepository.findAllByFileId(fileId)
                .stream()
                .mapToLong(ChatFileChunk::getEndMillis)
                .max()
                .orElseThrow(() -> new IllegalStateException("영상 길이 정보를 찾을 수 없습니다. fileId=" + fileId));

        if (videoEndMillis < targetDurationMillis) {
            throw new IllegalArgumentException("원본 영상이 요청한 쇼츠 길이보다 짧습니다. videoEndMillis="
                    + videoEndMillis + ", targetDurationMillis=" + targetDurationMillis);
        }

        List<VideoHighlightSegment> availableCandidates =
                candidates;

        if (
                excludeSegments != null
                        && !excludeSegments.isEmpty()
        ) {
            availableCandidates =
                    candidates.stream()
                            .filter(
                                    candidate ->
                                            excludeSegments.stream()
                                                    .noneMatch(
                                                            excluded ->
                                                                    isOverlapping(
                                                                            fitDuration(candidate, targetDurationMillis, videoEndMillis),
                                                                            excluded
                                                                    )
                                                    )
                            )
                            .toList();
        }

        if (availableCandidates.isEmpty()) {
            throw new IllegalStateException(
                    "쇼츠로 사용할 영상 후보 구간이 없습니다."
            );
        }

        // 시간 중복을 먼저 제거한 뒤, 이미 선택한 쇼츠와 주제가 다른 후보를 우선한다.
        // 모든 후보가 같은 주제라면 시간 중복이 없는 기존 후보 목록으로 되돌린다.
        if (excludeSegments != null && !excludeSegments.isEmpty()) {
            List<VideoHighlightSegment> distinctTopicCandidates = availableCandidates.stream()
                    .filter(candidate -> excludeSegments.stream()
                            .noneMatch(excluded -> isSameTopic(candidate.reason(), excluded.reason())))
                    .toList();

            if (!distinctTopicCandidates.isEmpty()) {
                log.info("쇼츠 주제 중복 후보 제외. fileId={}, beforeCount={}, afterCount={}",
                        fileId, availableCandidates.size(), distinctTopicCandidates.size());
                availableCandidates = distinctTopicCandidates;
            } else {
                log.info("쇼츠 다른 주제 후보 없음. 시간 중복 없는 후보로 선택 계속. fileId={}, candidateCount={}",
                        fileId, availableCandidates.size());
            }
        }

        String previousTopics = (excludeSegments == null || excludeSegments.isEmpty())
                ? "없음 (첫 번째 쇼츠)"
                : excludeSegments.stream()
                .map(VideoHighlightSegment::reason)
                .filter(StringUtils::hasText)
                .distinct()
                .reduce((first, second) -> first + System.lineSeparator() + "- " + second)
                .map(value -> "- " + value)
                .orElse("없음");

        Map<Integer, VideoHighlightSegment> candidateMap =
                new LinkedHashMap<>();

        StringBuilder candidateText =
                new StringBuilder();

        for (
                int index = 0;
                index < availableCandidates.size();
                index++
        ) {
            int candidateId =
                    index + 1;

            VideoHighlightSegment candidate =
                    availableCandidates.get(
                            index
                    );

            candidateMap.put(
                    candidateId,
                    candidate
            );

            candidateText
                    .append(
                            "[candidateId="
                    )
                    .append(
                            candidateId
                    )
                    .append(
                            "]"
                    )
                    .append(
                            System.lineSeparator()
                    )
                    .append(
                            "startMillis: "
                    )
                    .append(
                            candidate.startMillis()
                    )
                    .append(
                            System.lineSeparator()
                    )
                    .append(
                            "endMillis: "
                    )
                    .append(
                            candidate.endMillis()
                    )
                    .append(
                            System.lineSeparator()
                    )
                    .append(
                            "durationMillis: "
                    )
                    .append(
                            candidate.endMillis()
                                    - candidate.startMillis()
                    )
                    .append(
                            System.lineSeparator()
                    )
                    .append(
                            "reason: "
                    )
                    .append(
                            candidate.reason()
                    )
                    .append(
                            System.lineSeparator()
                    )
                    .append(
                            "suggestedTitle: "
                    )
                    .append(
                            StringUtils.hasText(candidate.suggestedTitle())
                                    ? candidate.suggestedTitle()
                                    : "없음"
                    )
                    .append(
                            System.lineSeparator()
                    )
                    .append(
                            System.lineSeparator()
                    );
        }

        String prompt =
                """
                다음은 하나의 긴 영상에서 이미 분석된 하이라이트 후보들입니다.

                이 후보들 중 독립적인 짧은 세로형 영상 하나로 편집했을 때
                가장 가치가 높은 후보 하나를 선택하세요.

                목표 영상 길이:
                약 %d초

                선택 기준 (아래 순서대로 비교하세요):
                1. 시작 3초의 흡입력: 도입부터 궁금증, 강한 대사, 예상 밖의 반응 등
                   바로 보고 싶게 만드는 요소가 있는가?
                2. 독립적 이해 가능성: 원본의 앞뒤 장면을 몰라도 누가 무엇을
                   왜 하는지 이해할 수 있는가?
                3. 재미와 몰입도: 웃음, 반전, 놀람, 긴장, 감동, 솔직한 고백처럼
                   영상 안에서 확인되는 구체적 사건과 반응이 있는가?
                4. 짧은 구간의 완결성: 하나의 대화나 사건이 진행되어 의미 있는
                   반응 또는 마무리까지 이어지는가?
                5. 제목 적합성: 후보의 suggestedTitle이 실제 장면과 일치하고
                   짧은 쇼츠의 핵심 사건을 잘 드러내는가?
                - 단순히 줄거리에서 중요하다는 이유만으로 선택하지 마세요.
                - 후보 reason과 suggestedTitle에서 확인할 수 있는 근거만 평가하고,
                  실제 영상에 없는 장면이나 감정을 추측하지 마세요.
                - 단순 이동, 반복 장면, 의미 없는 화면 변화는 선택하지 마세요.
                - 원본 영상 전체를 요약하려고 하지 마세요.
                - 여러 후보를 합치지 마세요.
                - 같은 인물이 등장해도 사건이나 대화 주제가 다르면 선택할 수 있습니다.
                - 이전에 선택한 쇼츠와 실질적으로 같은 사건이나 대화 주제인 후보는 피하세요.
                - 이전 쇼츠와 다른 주제인 후보 중 가장 흥미로운 장면을 선택하세요.
                - 다른 주제가 전혀 없다면 남은 후보 중 가장 흥미로운 장면을 선택하세요.
                - 반드시 제공된 candidateId 하나만 선택하세요.
                - 새로운 timestamp를 만들지 마세요.

                JSON 객체만 반환하세요.

                형식:
                {
                  "candidateId": 3
                }

                이미 선택한 쇼츠의 주제:
                %s

                후보:
                %s
                """.formatted(
                        targetDurationMillis / 1000,
                        previousTopics,
                        candidateText
                );

        ChatModelResponse response =
                chatModelProvider.chatOnce(
                        new ChatModelRequest(
                                ChatModelType.TEXT,
                                List.of(
                                        new ChatModelMessage(
                                                "system",
                                                """
                                                당신은 긴 영상에서 짧은 숏폼 영상으로
                                                사용할 가치가 가장 높은 장면을 선정하는 영상 편집자입니다.

                                                시작 3초의 흡입력, 독립적인 이해 가능성,
                                                재미·몰입도와 구간의 완결성을 우선하세요.
                                                제공된 후보 중 하나만 선택하고
                                                제공되지 않은 장면이나 시간을 만들지 마세요.
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
            throw new IllegalStateException(
                    "AI가 쇼츠 후보를 선택하지 못했습니다."
            );
        }

        Integer candidateId =
                parseCandidateId(
                        response.content()
                );

        VideoHighlightSegment selected =
                candidateMap.get(
                        candidateId
                );

        if (selected == null) {
            throw new IllegalStateException(
                    "AI가 유효하지 않은 쇼츠 후보를 선택했습니다. candidateId="
                            + candidateId
            );
        }

        VideoHighlightSegment fitted =
                fitDuration(
                        selected,
                        targetDurationMillis,
                        videoEndMillis
                );

        log.info(
                "쇼츠 하이라이트 선정 완료. fileId={}, candidateId={}, startMillis={}, endMillis={}, durationMillis={}, reason={}",
                fileId,
                candidateId,
                fitted.startMillis(),
                fitted.endMillis(),
                fitted.endMillis()
                        - fitted.startMillis(),
                fitted.reason()
        );

        return fitted;
    }

    private boolean isSameTopic(String firstReason, String secondReason) {
        if (!StringUtils.hasText(firstReason) || !StringUtils.hasText(secondReason)) {
            return false;
        }

        Set<String> firstKeywords = topicKeywords(firstReason);
        Set<String> secondKeywords = topicKeywords(secondReason);
        if (firstKeywords.isEmpty() || secondKeywords.isEmpty()) {
            return false;
        }
        int smallerSize = Math.min(firstKeywords.size(), secondKeywords.size());
        firstKeywords.retainAll(secondKeywords);

        // 동일 인물 이름 하나만 겹친다고 같은 사건으로 판단하지 않는다.
        // 공통 핵심 단어가 2개 이상이고, 짧은 쪽 설명의 절반 이상이 일치해야 한다.
        return firstKeywords.size() >= 2
                && (double) firstKeywords.size() / smallerSize >= 0.5;
    }

    private Set<String> topicKeywords(String reason) {
        Set<String> keywords = new HashSet<>();
        for (String raw : reason.split("[^\\p{L}\\p{N}]+")) {
            String word = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (word.length() < 2 || GENERIC_TOPIC_WORDS.contains(word)) {
                continue;
            }
            // 흔한 조사만 제거해 같은 고유명사의 조사 차이를 흡수한다.
            String stem = word.replaceFirst("(에서는|에게서|으로는|이라는|에게|에서|으로|와는|과는|에는|이나|라고|부터|까지|의|은|는|이|가|을|를|와|과|도|만)$", "");
            if (stem.length() >= 2 && !GENERIC_TOPIC_WORDS.contains(stem)) {
                keywords.add(stem);
            }
        }
        return keywords;
    }

    private Integer parseCandidateId(
            String response
    ) {
        try {
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

            int objectStart =
                    normalized.indexOf(
                            "{"
                    );

            int objectEnd =
                    normalized.lastIndexOf(
                            "}"
                    );

            if (
                    objectStart >= 0
                            && objectEnd > objectStart
            ) {
                normalized =
                        normalized.substring(
                                objectStart,
                                objectEnd + 1
                        );
            }

            Object parsed =
                    objectMapper.readValue(
                            normalized,
                            Object.class
                    );

            if (parsed instanceof Map<?, ?> map) {
                Object candidateId =
                        map.get(
                                "candidateId"
                        );

                if (candidateId instanceof Number number) {
                    return number.intValue();
                }

                if (candidateId != null) {
                    return Integer.parseInt(
                            candidateId
                                    .toString()
                                    .trim()
                    );
                }
            }
        } catch (Exception exception) {
            log.warn(
                    "쇼츠 candidateId JSON 파싱 실패. response={}",
                    response
            );
        }

        String digits =
                response.replaceAll(
                        "[^0-9]",
                        ""
                );

        if (digits.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(
                    digits
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private VideoHighlightSegment fitDuration(
            VideoHighlightSegment segment,
            long targetDurationMillis,
            long videoEndMillis
    ) {
        long start = segment.startMillis();
        long end = segment.endMillis();
        long duration = end - start;

        if (duration <= 0) {
            throw new IllegalArgumentException("하이라이트 후보 구간이 올바르지 않습니다.");
        }

        if (duration >= targetDurationMillis) {
            return new VideoHighlightSegment(
                    start,
                    start + targetDurationMillis,
                    segment.reason(),
                    segment.suggestedTitle()
            );
        }

        // 핵심 구간을 중앙에 두고 부족한 시간을 앞뒤로 확장한다.
        long missing = targetDurationMillis - duration;
        long fittedStart = Math.max(0L, start - missing / 2);
        long fittedEnd = fittedStart + targetDurationMillis;

        // 영상 끝에 가까운 후보라면 시작 지점을 앞으로 이동한다.
        if (fittedEnd > videoEndMillis) {
            fittedEnd = videoEndMillis;
            fittedStart = fittedEnd - targetDurationMillis;
        }

        return new VideoHighlightSegment(
                fittedStart,
                fittedEnd,
                segment.reason(),
                segment.suggestedTitle()
        );
    }

    private boolean isOverlapping(
            VideoHighlightSegment first,
            VideoHighlightSegment second
    ) {
        return first.startMillis()
                < second.endMillis()
                && second.startMillis()
                < first.endMillis();
    }
}