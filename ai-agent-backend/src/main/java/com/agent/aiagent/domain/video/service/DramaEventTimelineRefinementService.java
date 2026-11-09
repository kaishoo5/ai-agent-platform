
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
public class DramaEventTimelineRefinementService {

    private static final int BATCH_SIZE = 12;
    private static final long MAX_GROUP_SPAN_MILLIS = 180_000L;
    private static final long MAX_GAP_MILLIS = 60_000L;

    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;

    public RefineResponse refine(RefineRequest request) {
        if (request == null || request.fileId() == null || request.fileId().isBlank()
                || request.events() == null || request.events().isEmpty()) {
            throw new IllegalArgumentException("fileId와 events가 필요합니다.");
        }

        List<DramaEventTimelineService.Event> source = request.events().stream()
                .filter(e -> e != null && e.startMillis() >= 0 && e.endMillis() > e.startMillis())
                .sorted(Comparator.comparingLong(DramaEventTimelineService.Event::startMillis)
                        .thenComparingLong(DramaEventTimelineService.Event::endMillis))
                .toList();

        if (source.isEmpty()) {
            throw new IllegalArgumentException("유효한 사건이 없습니다.");
        }

        List<IndexedEvent> indexed = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            indexed.add(new IndexedEvent(i + 1, source.get(i)));
        }

        List<RefinedEvent> refined = new ArrayList<>();
        List<BatchDiagnostic> diagnostics = new ArrayList<>();

        for (int offset = 0; offset < indexed.size(); offset += BATCH_SIZE) {
            List<IndexedEvent> batch = indexed.subList(
                    offset,
                    Math.min(indexed.size(), offset + BATCH_SIZE)
            );

            try {
                String raw = requestModel(buildPrompt(batch));
                List<RefinedEvent> parsed = parseAndValidate(raw, batch);

                if (parsed.isEmpty()) {
                    throw new IllegalStateException("검증을 통과한 정제 사건이 없습니다.");
                }

                refined.addAll(parsed);
                diagnostics.add(new BatchDiagnostic(
                        batch.getFirst().id(),
                        batch.getLast().id(),
                        true,
                        ""
                ));

            } catch (Exception ex) {
                log.warn(
                        "타임라인 정제 실패, 원본 사건 유지. ids={}~{}, message={}",
                        batch.getFirst().id(),
                        batch.getLast().id(),
                        ex.getMessage()
                );

                for (IndexedEvent item : batch) {
                    refined.add(fromOriginal(item));
                }

                diagnostics.add(new BatchDiagnostic(
                        batch.getFirst().id(),
                        batch.getLast().id(),
                        false,
                        ex.getMessage()
                ));
            }
        }

        refined.sort(Comparator.comparingLong(RefinedEvent::startMillis));

        return new RefineResponse(
                request.fileId(),
                source.size(),
                refined.size(),
                List.copyOf(refined),
                List.copyOf(diagnostics)
        );
    }

    private String buildPrompt(List<IndexedEvent> batch) {
        StringBuilder input = new StringBuilder();

        for (IndexedEvent item : batch) {
            DramaEventTimelineService.Event e = item.event();

            input.append("ID=").append(item.id())
                    .append(" TIME=").append(e.startMillis()).append("~").append(e.endMillis())
                    .append(" ACTORS=").append(e.actors())
                    .append(" ACTION=").append(e.action())
                    .append(" EVIDENCE=").append(e.evidence())
                    .append(" UNCERTAINTY=").append(e.uncertainty())
                    .append('\n');
        }

        return """
                당신은 드라마 분석 결과의 사건 통합 편집자입니다.
                입력은 오류가 있을 수 있는 자동 인식 자료입니다.

                목표:
                반복 대사, 단순 화면 묘사를 줄이고 실제 사건의 행동·변화·대립을 시간순으로 정리합니다.

                절대 규칙:
                1. 원본 사건 ID만 참조하세요. 존재하지 않는 ID를 만들지 마세요.
                2. 연속된 사건이고 동일한 상황임이 근거로 확인되는 경우에만 여러 ID를 병합하세요.
                3. 인물 이름은 입력 actors 또는 evidence에서 명시적으로 확인되는 경우에만 사용하세요.
                   오범석/오금석, 시은/시윤 등 발음이 비슷한 이름을 근거 없이 같은 사람으로 확정하지 마세요.
                4. 대사의 화자가 명확하지 않으면 인물에게 행동을 단정적으로 귀속하지 마세요.
                5. 사건의 원인·결과를 연결할 수 없으면 억지로 인과관계를 만들지 마세요.
                6. 인식 오류, 인물 식별 불확실성, 인과관계 불확실성은 uncertainty에 기록하세요.
                7. 의미 없는 반복 대사와 장면 설명은 importance를 LOW로 지정할 수 있습니다.
                8. 모든 입력 ID는 정확히 한 번씩 결과의 sourceIds에 포함해야 합니다. 삭제하지 마세요.
                9. 시간 범위는 입력에 있는 시간만 사용하세요. 시간은 서버가 원본으로 다시 계산합니다.
                10. 반드시 JSON 객체만 출력하세요. 마크다운은 금지합니다.

                형식:
                {
                    "groups": [
                        {
                            "sourceIds": [1, 2],
                            "summary": "무슨 일이 일어났는지 구체적인 한국어 한두 문장",
                            "actors": [],
                            "importance": "HIGH",
                            "uncertainty": "불확실한 부분"
                        }
                    ]
                }

                importance는 HIGH, MEDIUM, LOW 중 하나입니다.
                단순히 '갈등 고조', '대화가 이어짐' 같은 추상적인 표현 대신 실제로 확인되는 행동을 쓰세요.

                원본 사건:
                """ + input;
    }

    private List<RefinedEvent> parseAndValidate(String raw, List<IndexedEvent> batch) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("모델 응답이 비어 있습니다.");
        }

        String cleaned = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
        int first = cleaned.indexOf('{');
        int last = cleaned.lastIndexOf('}');

        if (first < 0 || last <= first) {
            throw new IllegalStateException("JSON 객체가 없습니다.");
        }

        JsonNode groups = objectMapper.readTree(
                cleaned.substring(first, last + 1)
        ).path("groups");

        if (!groups.isArray()) {
            throw new IllegalStateException("groups 배열이 없습니다.");
        }

        Map<Integer, DramaEventTimelineService.Event> byId = new HashMap<>();

        for (IndexedEvent item : batch) {
            byId.put(item.id(), item.event());
        }

        Set<Integer> seen = new HashSet<>();
        List<RefinedEvent> result = new ArrayList<>();

        for (JsonNode group : groups) {
            JsonNode idsNode = group.path("sourceIds");

            if (!idsNode.isArray() || idsNode.isEmpty()) {
                throw new IllegalStateException("sourceIds가 없습니다.");
            }

            List<Integer> ids = new ArrayList<>();

            for (JsonNode idNode : idsNode) {
                int id = idNode.asInt(-1);

                if (!byId.containsKey(id) || !seen.add(id)) {
                    throw new IllegalStateException(
                            "존재하지 않거나 중복된 ID=" + id
                    );
                }

                ids.add(id);
            }

            ids.sort(Integer::compareTo);

            long start = Long.MAX_VALUE;
            long end = 0;
            long previousEnd = -1;

            LinkedHashSet<String> originalActors = new LinkedHashSet<>();
            List<String> evidence = new ArrayList<>();
            List<String> uncertainty = new ArrayList<>();

            for (int id : ids) {
                DramaEventTimelineService.Event e = byId.get(id);

                if (previousEnd >= 0
                        && e.startMillis() - previousEnd > MAX_GAP_MILLIS) {
                    throw new IllegalStateException(
                            "서로 멀리 떨어진 사건 병합 ID=" + ids
                    );
                }

                previousEnd = Math.max(previousEnd, e.endMillis());

                start = Math.min(start, e.startMillis());
                end = Math.max(end, e.endMillis());

                if (e.actors() != null) {
                    originalActors.addAll(e.actors());
                }

                evidence.add("[ID=" + id + "] " + safe(e.evidence()));

                if (e.uncertainty() != null && !e.uncertainty().isBlank()) {
                    uncertainty.add(e.uncertainty());
                }
            }

            if (end - start > MAX_GROUP_SPAN_MILLIS) {
                throw new IllegalStateException(
                        "병합 사건이 3분을 초과합니다. IDs=" + ids
                );
            }

            String summary = group.path("summary").asText("").trim();

            if (summary.isBlank()) {
                throw new IllegalStateException("summary가 비었습니다.");
            }

            String importance = group.path("importance")
                    .asText("MEDIUM")
                    .trim()
                    .toUpperCase();

            if (!Set.of("HIGH", "MEDIUM", "LOW").contains(importance)) {
                importance = "MEDIUM";
            }

            // 모델이 만든 인물 이름은 신뢰하지 않고,
            // 원본 actors에 명시된 이름만 결과에 보존한다.
            String modelUncertainty = group.path("uncertainty")
                    .asText("")
                    .trim();

            if (!modelUncertainty.isBlank()) {
                uncertainty.add(modelUncertainty);
            }

            result.add(new RefinedEvent(
                    start,
                    end,
                    List.copyOf(ids),
                    summary,
                    List.copyOf(originalActors),
                    importance,
                    String.join(" | ", evidence),
                    String.join(" | ", new LinkedHashSet<>(uncertainty))
            ));
        }

        if (seen.size() != batch.size()) {
            throw new IllegalStateException(
                    "원본 사건이 누락됐습니다. expected="
                            + batch.size()
                            + ", actual="
                            + seen.size()
            );
        }

        return result;
    }

    private RefinedEvent fromOriginal(IndexedEvent item) {
        DramaEventTimelineService.Event e = item.event();

        return new RefinedEvent(
                e.startMillis(),
                e.endMillis(),
                List.of(item.id()),
                safe(e.action()),
                e.actors() == null ? List.of() : e.actors(),
                "MEDIUM",
                "[ID=" + item.id() + "] " + safe(e.evidence()),
                safe(e.uncertainty())
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String requestModel(String prompt) {
        var response = chatModelProvider.chatOnce(
                new ChatModelRequest(
                        ChatModelType.TEXT,
                        List.of(
                                new ChatModelMessage(
                                        "system",
                                        "근거에 충실한 한국어 사건 통합 분석기입니다. JSON만 출력합니다."
                                ),
                                new ChatModelMessage("user", prompt)
                        ),
                        List.of()
                )
        );

        if (response == null || response.content() == null) {
            throw new IllegalStateException("모델 응답이 없습니다.");
        }

        return response.content();
    }

    private record IndexedEvent(
            int id,
            DramaEventTimelineService.Event event
    ) {
    }

    public record RefineRequest(
            String fileId,
            List<DramaEventTimelineService.Event> events
    ) {
    }

    public record RefinedEvent(
            long startMillis,
            long endMillis,
            List<Integer> sourceIds,
            String summary,
            List<String> actors,
            String importance,
            String evidence,
            String uncertainty
    ) {
    }

    public record BatchDiagnostic(
            int firstSourceId,
            int lastSourceId,
            boolean success,
            String error
    ) {
    }

    public record RefineResponse(
            String fileId,
            int sourceEventCount,
            int refinedEventCount,
            List<RefinedEvent> events,
            List<BatchDiagnostic> batches
    ) {
    }
}
