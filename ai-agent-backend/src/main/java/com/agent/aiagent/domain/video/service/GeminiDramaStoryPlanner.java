package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.video.model.*;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gemini의 저장된 장면 분석을 전 회차 통합 평가하여 STORY 편집 계획을 만든다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiDramaStoryPlanner {
    private static final Pattern SCENE_TIME = Pattern.compile("(?<![0-9])(?:\\*\\*)?(\\d{1,2}:\\d{2}(?::\\d{2})?)(?:\\*\\*)?(?![0-9:])");
    private static final long MIN_SCENE = 12_000L;
    private static final long MAX_SCENE = 75_000L;
    private static final int MAX_SCENES = 240;
    private static final int MAX_LINE = 260;

    private final ChatFileRepository chatFileRepository;
    private final ChatFileChunkRepository chunkRepository;
    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;

    public DramaEditPlan plan(List<String> fileIds, long targetMillis) {
        if (fileIds == null || fileIds.isEmpty() || targetMillis <= 0) {
            throw new IllegalArgumentException("회차 목록과 목표 길이가 필요합니다.");
        }
        List<Scene> scenes = new ArrayList<>();
        for (int i = 0; i < fileIds.size(); i++) {
            String fileId = fileIds.get(i);
            ChatFile file = chatFileRepository.findById(fileId)
                    .orElseThrow(() -> new IllegalArgumentException("영상 파일 없음: " + fileId));
            List<ChatFileChunk> chunks = chunkRepository.findAllByFileId(fileId).stream()
                    .filter(c -> c.getStartMillis() != null && c.getEndMillis() != null
                            && c.getEndMillis() > c.getStartMillis())
                    .sorted(Comparator.comparingLong(ChatFileChunk::getStartMillis))
                    .toList();
            long duration = chunks.stream().mapToLong(ChatFileChunk::getEndMillis).max().orElse(0);
            if (duration <= 0) throw new IllegalStateException("영상 분석 시간 정보 없음: " + fileId);
            TreeMap<Long, String> timestamps = new TreeMap<>();
            for (ChatFileChunk chunk : chunks) {
                if (!StringUtils.hasText(chunk.getContent())) continue;
                for (String line : chunk.getContent().split("\\R")) {
                    Matcher matcher = SCENE_TIME.matcher(line);
                    if (!matcher.find()) continue;
                    long at = parseTime(matcher.group(1));
                    if (at >= chunk.getStartMillis() && at < chunk.getEndMillis() && at < duration) {
                        String description = line.strip();
                        timestamps.putIfAbsent(at, description.substring(0, Math.min(MAX_LINE, description.length())));
                    }
                }
            }
            if (timestamps.isEmpty()) {
                throw new IllegalStateException("Gemini 장면별 시각 데이터 없음: " + file.getOriginalName());
            }
            List<Long> starts = new ArrayList<>(timestamps.keySet());
            for (int j = 0; j < starts.size(); j++) {
                long start = starts.get(j);
                long next = j + 1 < starts.size() ? starts.get(j + 1) : duration;
                // 장면 시작 시각만 확인된 상태이므로 다음 장면 시작 전까지만 사용한다.
                long end = Math.min(Math.min(next, start + MAX_SCENE), duration);
                if (end - start < MIN_SCENE) continue;
                scenes.add(new Scene(i + 1, fileId, start, end, timestamps.get(start)));
            }
            log.info("Gemini STORY 장면 추출. episode={}, sceneCount={}, durationMillis={}",
                    i + 1, starts.size(), duration);
        }
        if (scenes.isEmpty()) throw new IllegalStateException("사용 가능한 Gemini 장면이 없습니다.");
        if (scenes.size() > MAX_SCENES) {
            int originalCount = scenes.size();
            // 임의의 앞부분만 남기는 대신 전체 시간대에 고르게 분포시킨다.
            List<Scene> sampled = new ArrayList<>();
            for (int i = 0; i < MAX_SCENES; i++) {
                sampled.add(scenes.get((int) ((long) i * scenes.size() / MAX_SCENES)));
            }
            scenes = sampled;
            log.warn("Gemini STORY 후보 수 제한. originalCount={}, sampledCount={}",
                    originalCount, sampled.size());
        }

        // 회차별로 사건의 원인-전개-결과를 설명하는 장면을 먼저 선정한다.
        // 전체 후보를 한 번에 순위화하면 Gemini가 극히 일부만 반환하는 문제가 있었다.
        List<Integer> rankedIds = rankStoryScenes(scenes, fileIds.size(), targetMillis);
        LinkedHashSet<Integer> selectedIds = new LinkedHashSet<>();
        long[] episodeDurations = new long[fileIds.size() + 1];
        long total = 0;
        long episodeTarget = targetMillis / fileIds.size();

        // 회차별로 이야기의 핵심 장면을 확보하되, 한 회차가 전체 길이를 독점하지 않는다.
        for (int episode = 1; episode <= fileIds.size(); episode++) {
            for (int id : rankedIds) {
                Scene scene = scenes.get(id - 1);
                if (scene.episode() != episode || selectedIds.contains(id)) continue;
                if (total + scene.duration() > targetMillis) continue;
                if (episodeDurations[episode] + scene.duration() > episodeTarget) continue;
                selectedIds.add(id);
                episodeDurations[episode] += scene.duration();
                total += scene.duration();
            }
        }

        // 중요 장면만으로 남은 분량을 채운다. 분석에서 선택하지 않은 장면을
        // 단순 시간순으로 추가하지 않는다. 분량보다 이야기의 연결성을 우선한다.
        for (int id : rankedIds) {
            if (selectedIds.contains(id)) continue;
            Scene scene = scenes.get(id - 1);
            if (total + scene.duration() > targetMillis) continue;
            if (episodeDurations[scene.episode()] >= episodeTarget) continue;
            selectedIds.add(id);
            episodeDurations[scene.episode()] += scene.duration();
            total += scene.duration();
        }
        if (total < targetMillis * 0.8) {
            log.warn("Gemini STORY 핵심 장면 부족. targetMillis={}, plannedMillis={}. "
                    + "무관한 장면으로 분량을 채우지 않습니다.", targetMillis, total);
        }

        final List<Scene> finalScenes = scenes;
        List<DramaEditSegment> segments = selectedIds.stream()
                .map(id -> finalScenes.get(id - 1))
                .sorted(Comparator.comparingInt(Scene::episode).thenComparingLong(Scene::start))
                .map(scene -> new DramaEditSegment(scene.episode(), scene.fileId(),
                        scene.start(), scene.end(), scene.description()))
                .toList();
        for (int episode = 1; episode <= fileIds.size(); episode++) {
            int episodeNumber = episode;
            long count = segments.stream().filter(segment -> segment.episodeNumber() == episodeNumber).count();
            log.info("Gemini STORY 회차별 편집 결과. episode={}, selected={}, durationMillis={}",
                    episode, count, episodeDurations[episode]);
        }
        log.info("Gemini 통합 STORY 편집 계획. scenes={}, selected={}, targetMillis={}, plannedMillis={}",
                scenes.size(), segments.size(), targetMillis, total);
        return new DramaEditPlan(DramaSummaryMode.STORY, targetMillis, total, segments);
    }

    private List<Integer> rankStoryScenes(List<Scene> scenes, int episodeCount, long targetMillis) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        long episodeTarget = targetMillis / episodeCount;

        for (int episode = 1; episode <= episodeCount; episode++) {
            List<Integer> episodeIds = new ArrayList<>();
            StringBuilder input = new StringBuilder();
            for (int i = 0; i < scenes.size(); i++) {
                Scene scene = scenes.get(i);
                if (scene.episode() != episode) continue;
                int id = i + 1;
                episodeIds.add(id);
                input.append(id).append(" | ")
                        .append(scene.start() / 1000).append('-').append(scene.end() / 1000)
                        .append("초 | ").append(scene.description()).append('\n');
            }
            if (episodeIds.isEmpty()) continue;

            String prompt = """
                    다음은 드라마 %d회 장면 목록입니다. STORY 요약 영상에서 이 회차의
                    사건 흐름을 이해할 수 있도록 핵심 장면을 선택하세요.
                    반드시 다음 흐름을 고려하세요:
                    - 사건이 시작된 이유와 인물의 동기
                    - 인물 관계와 갈등이 변화하는 과정
                    - 중요한 행동의 결과 및 다음 사건으로 이어지는 단서
                    - 결말이나 다음 회차로 연결되는 장면
                    단순히 자극적인 장면만 선택하지 말고 원인과 결과가 보이게 하세요.
                    가능한 한 시간순으로 10~20개 장면 번호를 선택하세요.
                    이 회차 목표 분량은 약 %d초입니다. 각 장면의 실제 길이도 고려하세요.
                    제공된 목록에 없는 사건이나 장면을 만들어내지 마세요.
                    JSON 객체 하나만 출력하세요. 예: {"candidateIds":[1,3,7]}

                    장면 목록:
                    %s
                    """.formatted(episode, episodeTarget / 1000, input);
            List<Integer> ranked = requestCandidateIds(prompt, scenes.size());
            Set<Integer> allowed = new HashSet<>(episodeIds);
            List<Integer> valid = ranked.stream().filter(allowed::contains).distinct().toList();
            log.info("Gemini STORY 회차별 사건 선정. episode={}, candidates={}, selected={}",
                    episode, episodeIds.size(), valid.size());
            if (valid.isEmpty()) {
                throw new IllegalStateException("Gemini가 " + episode + "회 핵심 장면을 선정하지 못했습니다.");
            }
            selected.addAll(valid);
        }

        // 전체 회차의 선택 장면을 다시 함께 보고 인과관계상 필요한 장면부터 우선한다.
        StringBuilder overview = new StringBuilder();
        for (int id : selected) {
            Scene scene = scenes.get(id - 1);
            overview.append(id).append(" | ").append(scene.episode()).append("회 | ")
                    .append(scene.start() / 1000).append("초 | ")
                    .append(scene.description()).append('\n');
        }
        String globalPrompt = """
                다음은 드라마 %d개 회차에서 각각 선정한 핵심 장면입니다.
                전체 줄거리를 처음 보는 시청자가 이해하도록 사건의 원인, 전개,
                인물 관계 변화, 결과를 연결하는 데 꼭 필요한 장면부터 중요도 순으로
                모든 후보 번호를 나열하세요. 자극적인 장면보다 맥락을 설명하는 장면을
                우선하세요. 각 회차의 도입과 마무리도 빠뜨리지 마세요.
                후보 번호를 임의로 만들지 마세요.
                JSON 객체 하나만 출력하세요. 예: {"candidateIds":[3,1,5]}

                장면 목록:
                %s
                """.formatted(episodeCount, overview);
        List<Integer> globalRanked = requestCandidateIds(globalPrompt, scenes.size());
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (int id : globalRanked) {
            if (selected.contains(id)) result.add(id);
        }
        // Gemini가 일부만 반환했더라도 회차별로 선정한 장면만 보충한다.
        result.addAll(selected);
        log.info("Gemini STORY 전체 사건 순위. shortlisted={}, globalRanked={}, usable={}",
                selected.size(), globalRanked.size(), result.size());
        return List.copyOf(result);
    }

    private List<Integer> requestCandidateIds(String prompt, int maxId) {
        try {
            ChatModelResponse response = chatModelProvider.chatOnce(new ChatModelRequest(
                    ChatModelType.TEXT,
                    List.of(
                            new ChatModelMessage("system", "당신은 드라마의 인과관계를 보존하는 STORY 영상 편집자입니다. JSON만 반환하세요."),
                            new ChatModelMessage("user", prompt)
                    ),
                    List.of()
            ));
            if (response == null || !StringUtils.hasText(response.content())) return List.of();
            String content = response.content().replace("```json", "").replace("```", "").trim();
            int begin = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (begin < 0 || end <= begin) return List.of();
            CandidateOrder parsed = objectMapper.readValue(content.substring(begin, end + 1), CandidateOrder.class);
            if (parsed == null || parsed.candidateIds() == null) return List.of();
            LinkedHashSet<Integer> valid = new LinkedHashSet<>();
            for (Integer id : parsed.candidateIds()) {
                if (id != null && id >= 1 && id <= maxId) valid.add(id);
            }
            return List.copyOf(valid);
        } catch (Exception exception) {
            log.warn("Gemini STORY 사건 장면 선정 실패. error={}", exception.toString());
            return List.of();
        }
    }

    private long parseTime(String value) {
        try {
            String[] p = value.split(":");
            long sec = p.length == 3
                    ? Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Long.parseLong(p[2])
                    : Long.parseLong(p[0]) * 60 + Long.parseLong(p[1]);
            return sec * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private record Scene(int episode, String fileId, long start, long end, String description) {
        long duration() { return end - start; }
    }
    public record CandidateOrder(List<Integer> candidateIds) { }
}
