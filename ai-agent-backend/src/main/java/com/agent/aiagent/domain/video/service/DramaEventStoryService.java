
package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaEventStoryService {

    private static final int MAX_EVENTS = 150;
    private static final int MAX_EVIDENCE_CHARS = 600;
    private static final int MAX_OUTPUT_EVENTS = 30;

    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;

    public StoryResponse generate(StoryRequest request) {

        if (request == null
                || request.fileId() == null
                || request.fileId().isBlank()
                || request.events() == null
                || request.events().isEmpty()) {
            throw new IllegalArgumentException("fileId와 events가 필요합니다.");
        }

        List<DramaEventTimelineRefinementService.RefinedEvent> events =
                request.events().stream()
                        .filter(e -> e != null
                                && e.startMillis() >= 0
                                && e.endMillis() > e.startMillis())
                        .sorted(Comparator.comparingLong(
                                DramaEventTimelineRefinementService.RefinedEvent::startMillis))
                        .toList();

        if (events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException(
                    "유효한 사건 수는 1~" + MAX_EVENTS + "개여야 합니다."
            );
        }

        Map<Integer, IndexedEvent> indexed = new HashMap<>();
        StringBuilder source = new StringBuilder();

        for (int i = 0; i < events.size(); i++) {
            int id = i + 1;
            var event = events.get(i);

            indexed.put(id, new IndexedEvent(id, event));

            source.append("\n[EVENT ").append(id).append("]\n")
                    .append("TIME: ")
                    .append(event.startMillis())
                    .append("~")
                    .append(event.endMillis())
                    .append("\n")
                    .append("IMPORTANCE: ")
                    .append(safe(event.importance()))
                    .append("\n")
                    .append("ACTORS: ")
                    .append(event.actors() == null ? List.of() : event.actors())
                    .append("\n")
                    .append("SUMMARY: ")
                    .append(safe(event.summary()))
                    .append("\n")
                    .append("EVIDENCE: ")
                    .append(limit(safe(event.evidence()), MAX_EVIDENCE_CHARS))
                    .append("\n")
                    .append("UNCERTAINTY: ")
                    .append(safe(event.uncertainty()))
                    .append("\n");
        }

        String raw = requestModel(buildPrompt(source.toString()));

        StoryResponse result = parseResponse(
                request.fileId(),
                events.size(),
                indexed,
                raw
        );

        log.info(
                "드라마 스토리 구성 완료. fileId={}, sourceEvents={}, storyEvents={}, relations={}",
                request.fileId(),
                events.size(),
                result.storyEvents().size(),
                result.relations().size()
        );

        return result;
    }

    private String buildPrompt(String source) {
        return """
                당신은 드라마 영상 분석 자료를 바탕으로
                전체 사건 흐름을 구성하는 스토리 분석가입니다.

                입력은 영상과 음성의 자동 분석 결과이며
                음성 인식 오류, 인물 이름 오류, 불완전한 화면 설명이 포함될 수 있습니다.

                목표:
                개별 사건을 단순 나열하지 말고
                전체 영상에서 실제로 확인되는 주요 사건과
                사건 사이의 연결 관계를 정리하세요.

                [중요 규칙]

                1. 모든 사건을 시간순으로 살펴보세요.
                2. 중요도가 HIGH 또는 MEDIUM인 사건을 우선 검토하되
                   LOW 사건도 중요한 연결 단서라면 사용할 수 있습니다.
                3. 스토리상 의미 없는 장면, 반복 대사, 배경 묘사는 제외할 수 있습니다.
                4. 인물의 행동과 사건을 구체적으로 설명하세요.
                5. 원본 근거가 없는 행동, 감정, 동기, 결과를 만들어내지 마세요.
                6. 이름이 비슷하다고 동일 인물로 단정하지 마세요.
                7. 특정 인물이 언급됐다는 이유만으로 그 인물이
                   직접 말하거나 행동했다고 단정하지 마세요.
                8. 사건의 시간 범위는 서버가 원본 사건을 기준으로 계산합니다.
                9. sourceEventIds에는 실제 입력 EVENT 번호만 사용하세요.
                10. 하나의 원본 EVENT를 여러 스토리 사건에서 재사용할 수 있지만
                    중복되는 스토리 사건은 만들지 마세요.
                11. 서로 다른 사건의 관계가 근거로 확인되지 않으면
                    관계를 만들지 마세요.
                12. 명확하지 않은 내용은 uncertainty에 기록하세요.
                13. 각 사건의 importance는 HIGH, MEDIUM, LOW 중 하나입니다.
                14. 최대 30개의 스토리 사건만 작성하세요.
                15. JSON 객체만 출력하고 마크다운은 사용하지 마세요.

                [스토리 구성 방법]

                첫째, 전체 사건을 읽고 중요한 갈등과 변화를 파악합니다.
                둘째, 같은 상황에 속하는 사건을 하나의 스토리 사건으로 묶습니다.
                셋째, 사건의 구체적인 내용을 한국어로 설명합니다.
                넷째, 근거가 있는 사건 사이의 관계를 연결합니다.
                다섯째, 전체 줄거리를 요약합니다.

                관계 유형:
                - SEQUENCE: 시간상 뒤이어 발생
                - CAUSE: 앞 사건이 뒤 사건의 원인임이 근거로 확인됨
                - CONSEQUENCE: 앞 사건의 결과가 뒤 사건에 나타남
                - RELATED: 같은 갈등이나 상황에 관련됨

                단순히 시간순으로 이어진다는 이유만으로
                CAUSE 또는 CONSEQUENCE를 사용하지 마세요.

                [응답 형식]

                {
                    "title": "영상 전체의 핵심 사건을 설명하는 제목",
                    "overallSummary": "전체 사건 흐름을 설명하는 한국어 문단",
                    "storyEvents": [
                        {
                            "id": 1,
                            "sourceEventIds": [1, 2],
                            "summary": "확인된 사건을 구체적으로 설명",
                            "importance": "HIGH",
                            "uncertainty": ""
                        }
                    ],
                    "relations": [
                        {
                            "from": 1,
                            "to": 2,
                            "type": "RELATED",
                            "reason": "두 사건의 관계를 뒷받침하는 근거"
                        }
                    ]
                }

                storyEvents의 id는 1부터 시작하는 연속된 번호를 사용하세요.
                relations의 from과 to는 storyEvents의 id를 참조합니다.

                [원본 사건]

                """ + source;
    }

    private StoryResponse parseResponse(
            String fileId,
            int sourceCount,
            Map<Integer, IndexedEvent> indexed,
            String raw
    ) {

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("스토리 모델 응답이 비어 있습니다.");
        }

        String cleaned = raw.replaceAll("(?s)<think>.*?</think>", "").trim();

        int first = cleaned.indexOf('{');
        int last = cleaned.lastIndexOf('}');

        if (first < 0 || last <= first) {
            throw new IllegalStateException("스토리 JSON 객체를 찾을 수 없습니다.");
        }

        JsonNode root = objectMapper.readTree(
                cleaned.substring(first, last + 1)
        );

        JsonNode storyNodes = root.path("storyEvents");

        if (!storyNodes.isArray() || storyNodes.isEmpty()) {
            throw new IllegalStateException("storyEvents가 비어 있습니다.");
        }

        if (storyNodes.size() > MAX_OUTPUT_EVENTS) {
            throw new IllegalStateException("스토리 사건 수가 제한을 초과했습니다.");
        }

        List<StoryEvent> storyEvents = new ArrayList<>();
        Set<Integer> storyIds = new HashSet<>();

        for (JsonNode node : storyNodes) {

            int storyId = node.path("id").asInt(-1);

            if (storyId <= 0 || !storyIds.add(storyId)) {
                throw new IllegalStateException(
                        "잘못된 스토리 사건 ID=" + storyId
                );
            }

            JsonNode sourceIdsNode = node.path("sourceEventIds");

            if (!sourceIdsNode.isArray() || sourceIdsNode.isEmpty()) {
                throw new IllegalStateException(
                        "스토리 사건의 sourceEventIds가 없습니다. id=" + storyId
                );
            }

            LinkedHashSet<Integer> sourceIds = new LinkedHashSet<>();

            long start = Long.MAX_VALUE;
            long end = 0;

            LinkedHashSet<String> actors = new LinkedHashSet<>();
            List<String> evidence = new ArrayList<>();
            List<String> uncertainties = new ArrayList<>();

            for (JsonNode idNode : sourceIdsNode) {

                int sourceId = idNode.asInt(-1);
                IndexedEvent item = indexed.get(sourceId);

                if (item == null || !sourceIds.add(sourceId)) {
                    throw new IllegalStateException(
                            "잘못되거나 중복된 원본 사건 ID=" + sourceId
                    );
                }

                var event = item.event();

                start = Math.min(start, event.startMillis());
                end = Math.max(end, event.endMillis());

                if (event.actors() != null) {
                    actors.addAll(event.actors());
                }

                evidence.add(
                        "[EVENT " + sourceId + "] " + safe(event.evidence())
                );

                if (event.uncertainty() != null
                        && !event.uncertainty().isBlank()) {
                    uncertainties.add(event.uncertainty());
                }
            }

            String summary = node.path("summary").asText("").trim();

            if (summary.isBlank()) {
                throw new IllegalStateException(
                        "스토리 사건의 summary가 비어 있습니다. id=" + storyId
                );
            }

            String importance = normalizeImportance(
                    node.path("importance").asText("MEDIUM")
            );

            String modelUncertainty = node.path("uncertainty")
                    .asText("")
                    .trim();

            if (!modelUncertainty.isBlank()) {
                uncertainties.add(modelUncertainty);
            }

            storyEvents.add(new StoryEvent(
                    storyId,
                    start,
                    end,
                    List.copyOf(sourceIds),
                    summary,
                    List.copyOf(actors),
                    importance,
                    String.join("\n", evidence),
                    String.join(" | ", new LinkedHashSet<>(uncertainties))
            ));
        }

        // 스토리 ID는 반드시 1부터 연속이어야 한다.
        for (int i = 1; i <= storyIds.size(); i++) {
            if (!storyIds.contains(i)) {
                throw new IllegalStateException(
                        "스토리 사건 ID가 연속되지 않습니다."
                );
            }
        }

        storyEvents.sort(
                Comparator.comparingLong(StoryEvent::startMillis)
                        .thenComparingInt(StoryEvent::id)
        );

        List<StoryRelation> relations = new ArrayList<>();
        JsonNode relationNodes = root.path("relations");

        if (relationNodes.isArray()) {
            for (JsonNode node : relationNodes) {

                int from = node.path("from").asInt(-1);
                int to = node.path("to").asInt(-1);

                String type = node.path("type")
                        .asText("")
                        .trim()
                        .toUpperCase();

                String reason = node.path("reason")
                        .asText("")
                        .trim();

                if (!storyIds.contains(from)
                        || !storyIds.contains(to)
                        || from == to
                        || !Set.of(
                        "SEQUENCE",
                        "CAUSE",
                        "CONSEQUENCE",
                        "RELATED"
                ).contains(type)
                        || reason.isBlank()) {

                    log.warn(
                            "잘못된 스토리 관계 제외. from={}, to={}, type={}",
                            from, to, type
                    );
                    continue;
                }

                relations.add(new StoryRelation(
                        from,
                        to,
                        type,
                        reason
                ));
            }
        }

        String title = root.path("title").asText("").trim();
        String overallSummary = root.path("overallSummary")
                .asText("")
                .trim();

        if (title.isBlank() || overallSummary.isBlank()) {
            throw new IllegalStateException(
                    "스토리 제목 또는 전체 요약이 비어 있습니다."
            );
        }

        return new StoryResponse(
                fileId,
                sourceCount,
                storyEvents.size(),
                title,
                overallSummary,
                List.copyOf(storyEvents),
                List.copyOf(relations)
        );
    }

    private String requestModel(String prompt) {

        var response = chatModelProvider.chatOnce(
                new ChatModelRequest(
                        ChatModelType.TEXT,
                        List.of(
                                new ChatModelMessage(
                                        "system",
                                        "당신은 드라마 사건을 근거 기반으로 연결하는 스토리 분석가입니다. JSON만 출력하세요."
                                ),
                                new ChatModelMessage("user", prompt)
                        ),
                        List.of()
                )
        );

        if (response == null
                || response.content() == null
                || response.content().isBlank()) {
            throw new IllegalStateException("모델 응답이 없습니다.");
        }

        return response.content();
    }

    private String normalizeImportance(String value) {

        String normalized = safe(value).toUpperCase();

        return Set.of("HIGH", "MEDIUM", "LOW").contains(normalized)
                ? normalized
                : "MEDIUM";
    }

    private String limit(String value, int maxLength) {
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record IndexedEvent(
            int id,
            DramaEventTimelineRefinementService.RefinedEvent event
    ) {
    }

    public record StoryRequest(
            String fileId,
            List<DramaEventTimelineRefinementService.RefinedEvent> events
    ) {
    }

    public record StoryEvent(
            int id,
            long startMillis,
            long endMillis,
            List<Integer> sourceEventIds,
            String summary,
            List<String> actors,
            String importance,
            String evidence,
            String uncertainty
    ) {
    }

    public record StoryRelation(
            int from,
            int to,
            String type,
            String reason
    ) {
    }

    public record StoryResponse(
            String fileId,
            int sourceEventCount,
            int storyEventCount,
            String title,
            String overallSummary,
            List<StoryEvent> storyEvents,
            List<StoryRelation> relations
    ) {
    }
}
