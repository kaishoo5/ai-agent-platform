package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoShortsHighlightSelector {

    private final VideoHighlightSelector videoHighlightSelector;
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
                                                                            candidate,
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

                선택 기준:
                - 짧은 영상 하나만 봐도 상황이나 사건을 이해할 수 있어야 합니다.
                - 시작부터 끝까지 하나의 사건이나 대화 흐름이 자연스럽게 이어져야 합니다.
                - 중요한 사건, 강한 반응, 흥미로운 대화, 갈등, 반전, 웃긴 상황 등
                  짧은 영상으로 봤을 때 관심을 끌 만한 장면을 우선하세요.
                - 단순 이동, 반복 장면, 의미 없는 화면 변화는 선택하지 마세요.
                - 원본 영상 전체를 요약하려고 하지 마세요.
                - 여러 후보를 합치지 마세요.
                - 반드시 제공된 candidateId 하나만 선택하세요.
                - 새로운 timestamp를 만들지 마세요.

                JSON 객체만 반환하세요.

                형식:
                {
                  "candidateId": 3
                }

                후보:
                %s
                """.formatted(
                        targetDurationMillis / 1000,
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
                        targetDurationMillis
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
            long targetDurationMillis
    ) {
        long durationMillis =
                segment.endMillis()
                        - segment.startMillis();

        if (durationMillis <= targetDurationMillis) {
            return segment;
        }

        return new VideoHighlightSegment(
                segment.startMillis(),
                segment.startMillis()
                        + targetDurationMillis,
                segment.reason()
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