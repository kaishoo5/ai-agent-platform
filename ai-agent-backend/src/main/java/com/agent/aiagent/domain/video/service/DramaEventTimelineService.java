package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaEventTimelineService {

    private static final long WINDOW_MILLIS = 180_000L;
    private static final int MAX_WINDOW_CHARS = 12000;

    private final ChatFileChunkRepository chunkRepository;
    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;

    public TimelineResponse preview(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId가 없습니다.");
        }
        List<ChatFileChunk> chunks = chunkRepository.findAllByFileId(fileId).stream()
                .filter(c -> c.getStartMillis() != null && c.getEndMillis() != null)
                .filter(c -> c.getEndMillis() > c.getStartMillis())
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .sorted(Comparator.comparing(ChatFileChunk::getStartMillis)
                        .thenComparingInt(ChatFileChunk::getChunkIndex))
                .toList();
        if (chunks.isEmpty()) {
            throw new IllegalStateException("시간 정보가 있는 분석 청크가 없습니다. fileId=" + fileId);
        }
        long durationMillis = chunks.stream().mapToLong(ChatFileChunk::getEndMillis).max().orElse(0);
        List<WindowResult> windows = new ArrayList<>();
        for (long start = 0; start < durationMillis; start += WINDOW_MILLIS) {
            long end = Math.min(durationMillis, start + WINDOW_MILLIS);
            StringBuilder evidence = new StringBuilder();
            int usedChunks = 0;
            int omittedChunks = 0;
            for (ChatFileChunk chunk : chunks) {
                if (chunk.getStartMillis() >= end || chunk.getEndMillis() <= start) {
                    continue;
                }
                String line = "[" + chunk.getStartMillis() + "~" + chunk.getEndMillis()
                        + "ms, chunk=" + chunk.getChunkIndex() + "] " + chunk.getContent().strip() + "\n";
                if (evidence.length() + line.length() > MAX_WINDOW_CHARS) {
                    omittedChunks++;
                    continue;
                }
                evidence.append(line);
                usedChunks++;
            }
            if (usedChunks == 0) {
                windows.add(new WindowResult(start, end, 0, omittedChunks, List.of(), ""));
                continue;
            }
            String prompt = """
                    다음은 드라마 영상의 일부 구간에 대한 자동 분석 데이터입니다. 이것은 오류가 있을 수 있는 관찰 자료입니다.
                    원본 영상의 사건 타임라인을 만들기 위한 사실 추출만 수행하세요.

                    규칙:
                    1. 사건은 입력 데이터에서 직접 확인되는 경우에만 추출합니다.
                    2. 인물 이름, 관계, 사건 원인과 결과를 추측하지 않습니다.
                    3. 인식이 어색하거나 모순되는 발화는 억지로 교정하지 않고 uncertainty에 기록합니다.
                    4. 의상, 소품, 카메라 구도 등 사건과 무관한 묘사는 생략합니다.
                    5. 사건이 확인되지 않으면 events를 빈 배열로 반환합니다.
                    6. timestamp는 아래 청크의 실제 밀리초 시간값을 사용합니다. 임의로 만들지 않습니다.
                    7. 반드시 다음 형식의 JSON 객체만 반환합니다. 코드블록이나 설명은 쓰지 마세요.
                    {"events":[{"startMillis":0,"endMillis":0,"actors":[],"action":"확인된 사건","evidence":"입력 자료의 짧은 직접 근거","uncertainty":"불확실한 부분 또는 빈 문자열"}]}

                    구간: %d ~ %d ms
                    분석 데이터:
                    %s
                    """.formatted(start, end, evidence);
            String raw = chatModelProvider.chatOnce(new ChatModelRequest(
                    ChatModelType.TEXT,
                    List.of(new ChatModelMessage("system", "드라마 분석 자료에서 확인 가능한 사건만 추출하는 JSON 분석기입니다."),
                            new ChatModelMessage("user", prompt)),
                    List.of()
            )).content();
            List<Event> events = parseEvents(raw, start, end);
            windows.add(new WindowResult(start, end, usedChunks, omittedChunks, events, events.isEmpty() ? raw : ""));
            log.info("드라마 사건 타임라인 구간 완료. fileId={}, start={}, end={}, chunks={}, omitted={}, events={}",
                    fileId, start, end, usedChunks, omittedChunks, events.size());
        }
        List<Event> events = windows.stream().flatMap(w -> w.events().stream())
                .sorted(Comparator.comparingLong(Event::startMillis)).toList();
        return new TimelineResponse(fileId, durationMillis, chunks.size(), events, windows);
    }

    private List<Event> parseEvents(String raw, long windowStart, long windowEnd) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            String cleaned = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
            int first = cleaned.indexOf('{');
            int last = cleaned.lastIndexOf('}');
            if (first < 0 || last <= first) return List.of();
            JsonNode array = objectMapper.readTree(cleaned.substring(first, last + 1)).path("events");
            if (!array.isArray()) return List.of();
            List<Event> result = new ArrayList<>();
            for (JsonNode node : array) {
                long start = node.path("startMillis").asLong(-1);
                long end = node.path("endMillis").asLong(-1);
                String action = node.path("action").asText("").trim();
                String evidence = node.path("evidence").asText("").trim();
                if (start < windowStart || start >= windowEnd || end <= start || end > windowEnd
                        || action.isBlank() || evidence.isBlank()) continue;
                List<String> actors = new ArrayList<>();
                JsonNode actorArray = node.path("actors");
                if (actorArray.isArray()) actorArray.forEach(a -> actors.add(a.asText()));
                result.add(new Event(start, end, List.copyOf(actors), action, evidence,
                        node.path("uncertainty").asText("")));
            }
            return List.copyOf(result);
        } catch (Exception ex) {
            log.warn("드라마 사건 타임라인 JSON 파싱 실패. windowStart={}, message={}", windowStart, ex.getMessage());
            return List.of();
        }
    }

    public record Event(long startMillis, long endMillis, List<String> actors,
                        String action, String evidence, String uncertainty) { }
    public record WindowResult(long startMillis, long endMillis, int usedChunks,
                               int omittedChunks, List<Event> events, String rawResponseIfNoEvents) { }
    public record TimelineResponse(String fileId, long durationMillis, int totalChunks,
                                   List<Event> events, List<WindowResult> windows) { }
}
