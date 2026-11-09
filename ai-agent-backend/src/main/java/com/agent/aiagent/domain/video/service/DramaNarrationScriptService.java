package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaEditSegment;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaNarrationScriptService {

    private static final int MAX_EVIDENCE_CHARACTERS = 3500;
    private static final int MAX_OUTLINE_INPUT_PER_SCENE = 1000;
    private static final int MAX_OUTLINE_INPUT_TOTAL = 18000;
    private static final int MAX_STORY_OUTLINE_CHARACTERS = 4500;
    private static final int MAX_CONTEXT_CHARACTERS = 1100;
    private static final int MAX_PREVIOUS_SCRIPT_CHARACTERS = 550;
    private static final long CONTEXT_WINDOW_MILLIS = 30_000L;

    private final ChatModelProvider chatModelProvider;
    private final ChatFileChunkRepository chatFileChunkRepository;

    public String generate(DramaEditPlan plan) {
        return String.join(" ", generateScenes(plan));
    }

    public List<String> generateScenes(DramaEditPlan plan) {
        validatePlan(plan);

        Map<String, List<ChatFileChunk>> chunksByFile = new HashMap<>();
        List<String> scripts = new ArrayList<>();
        String previousScript = "";
        // 장면별 대본을 쓰기 전에 선정된 장면들의 사건 흐름을 한 번에 파악한다.
        // 청크는 파일별로 한 번만 조회하고, 전체 맥락에는 장면 근거만 시간순으로 제공한다.
        for (DramaEditSegment segment : plan.segments()) {
            if (segment.fileId() == null || segment.fileId().isBlank()) {
                throw new IllegalArgumentException("장면의 fileId가 없습니다.");
            }
            chunksByFile.computeIfAbsent(segment.fileId(), this::loadChunks);
        }
        String storyOutline = buildStoryOutline(plan, chunksByFile);
        log.info("나레이션 전체 사건 흐름 생성 완료. scenes={}, outlineChars={}, outline={}",
                plan.segments().size(), storyOutline.length(), storyOutline);

        for (int i = 0; i < plan.segments().size(); i++) {
            DramaEditSegment segment = plan.segments().get(i);
            if (segment.durationMillis() <= 0) {
                throw new IllegalArgumentException("장면 길이가 올바르지 않습니다. scene=" + (i + 1));
            }
            if (segment.fileId() == null || segment.fileId().isBlank()) {
                throw new IllegalArgumentException("장면의 fileId가 없습니다. scene=" + (i + 1));
            }

            List<ChatFileChunk> chunks = chunksByFile.getOrDefault(segment.fileId(), List.of());

            String evidence = collectEvidence(chunks, segment.startMillis(), segment.endMillis(), MAX_EVIDENCE_CHARACTERS);
            String before = collectEvidence(chunks,
                    Math.max(0, segment.startMillis() - CONTEXT_WINDOW_MILLIS),
                    segment.startMillis(), MAX_CONTEXT_CHARACTERS);
            String after = collectEvidence(chunks, segment.endMillis(),
                    segment.endMillis() + CONTEXT_WINDOW_MILLIS, MAX_CONTEXT_CHARACTERS);

            long durationSeconds = Math.max(1, segment.durationMillis() / 1000);
            int targetCharacters = (int) Math.max(12, Math.min(180, durationSeconds * 2));

            // 실제 분석 근거가 없으면 LLM이 선정 이유만으로 사건을 지어내지 않도록 한다.
            if (evidence.isBlank()) {
                log.warn("나레이션 근거 청크 없음. scene={}/{}, episode={}, fileId={}, range={}~{}",
                        i + 1, plan.segments().size(), segment.episodeNumber(), segment.fileId(),
                        segment.startMillis(), segment.endMillis());
                scripts.add("");
                continue;
            }

            String prompt = """
                    당신은 드라마 줄거리를 설명하는 한국어 내레이션 작가입니다.
                    화면의 물체를 묘사하는 사람이 아니라, 사건을 이해하기 쉽게 전달하는 해설자입니다.

                    [작성 원칙]
                    - 현재 장면의 사건, 인물의 행동과 갈등, 대화가 뜻하는 변화를 중심으로 설명하세요.
                    - 전체 사건 흐름은 맥락 참고용입니다. 현재 장면의 분석 근거로 뒷받침되는 사실만 단정하세요.
                    - 인물 이름과 관계는 근거에서 확인될 때만 사용하세요. 불확실하면 역할 또는 일반적인 표현을 사용하세요.
                    - 화면의 의상 색, 배경 소품, 표지판, 카메라 구도 등은 사건 이해에 꼭 필요하지 않으면 쓰지 마세요.
                    - 분석 내용이 단순 시각 묘사뿐이면 사건을 지어내지 말고, 확인 가능한 행동을 간결히 서술하세요.
                    - 선정 이유는 사실 근거가 아닙니다. 선정 이유와 실제 분석이 충돌하면 실제 분석을 우선하세요.
                    - 앞 장면의 내레이션을 반복하지 말고, 연결되는 사건이면 자연스럽게 이어가세요.
                    - 대사를 나열하거나 '이 장면에서는', '화면에는', '보인다', '갈등을 암시한다' 같은 분석 보고서 표현을 피하세요.
                    - 음성 합성에 적합한 자연스러운 한국어 서술문만 출력하세요. 제목, 번호, 설명, 마크다운은 금지합니다.
                    - %d자 이내로 작성하세요. 확실한 내용이 적으면 짧게 작성하세요.

                    [전체 사건 흐름 - 맥락용, 현재 장면의 직접 근거는 아님]
                    %s

                    [현재 장면]
                    회차: %d / 시작: %.1f초 / 길이: %d초
                    선정 이유(참고용): %s

                    [현재 장면 실제 분석 - 직접 근거]
                    %s

                    [직전 원본 맥락 - 참고용]
                    %s

                    [직후 원본 맥락 - 참고용]
                    %s

                    [이전 내레이션 - 반복 방지]
                    %s
                    """.formatted(
                    targetCharacters,
                    storyOutline.isBlank() ? "사건 흐름 확인 불가" : storyOutline,
                    segment.episodeNumber(),
                    segment.startMillis() / 1000.0,
                    durationSeconds,
                    safe(segment.reason(), 300),
                    evidence,
                    before.isBlank() ? "없음" : before,
                    after.isBlank() ? "없음" : after,
                    previousScript.isBlank() ? "없음" : previousScript
            );

            String sceneScript = requestScript(prompt);
            sceneScript = sanitize(sceneScript);
            if (sceneScript.length() > targetCharacters) {
                String shortened = sanitize(requestScript("""
                        다음 한국어 내레이션을 사건 의미를 유지하면서 %d자 이내로 줄이세요.
                        새로운 사건이나 이름을 추가하지 마세요. 자연스러운 서술문만 출력하세요.
                        원문: %s
                        """.formatted(targetCharacters, sceneScript)));
                if (!shortened.isBlank() && shortened.length() < sceneScript.length()) {
                    sceneScript = shortened;
                }
                if (sceneScript.length() > targetCharacters) {
                    sceneScript = trimAtSentenceBoundary(sceneScript, targetCharacters);
                }
            }

            scripts.add(sceneScript);
            if (!sceneScript.isBlank()) {
                previousScript = safe(sceneScript, MAX_PREVIOUS_SCRIPT_CHARACTERS);
            }

            log.info("장면별 나레이션 생성. scene={}/{}, episode={}, fileId={}, range={}~{}, evidenceChars={}, script={}",
                    i + 1, plan.segments().size(), segment.episodeNumber(), segment.fileId(),
                    segment.startMillis(), segment.endMillis(), evidence.length(), sceneScript);
            log.debug("나레이션 원본 근거. scene={}, evidence={}", i + 1, evidence);
        }

        return List.copyOf(scripts);
    }

    private List<ChatFileChunk> loadChunks(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return List.of();
        }
        return chatFileChunkRepository.findAllByFileId(fileId).stream()
                .filter(c -> c.getStartMillis() != null && c.getEndMillis() != null)
                .filter(c -> c.getEndMillis() > c.getStartMillis())
                .sorted(Comparator.comparing(ChatFileChunk::getStartMillis)
                        .thenComparingInt(ChatFileChunk::getChunkIndex))
                .toList();
    }

    private String buildStoryOutline(DramaEditPlan plan, Map<String, List<ChatFileChunk>> chunksByFile) {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < plan.segments().size(); i++) {
            DramaEditSegment segment = plan.segments().get(i);
            String evidence = collectEvidence(
                    chunksByFile.getOrDefault(segment.fileId(), List.of()),
                    segment.startMillis(), segment.endMillis(), MAX_OUTLINE_INPUT_PER_SCENE);
            if (evidence.isBlank()) {
                continue;
            }
            String entry = "장면 " + (i + 1) + " / 회차 " + segment.episodeNumber()
                    + " / " + (segment.startMillis() / 1000) + "초\n" + evidence + "\n";
            if (input.length() + entry.length() > MAX_OUTLINE_INPUT_TOTAL) {
                break;
            }
            input.append(entry);
        }
        if (input.isEmpty()) {
            return "";
        }
        String prompt = """
                다음은 드라마에서 실제로 선정된 장면들의 영상 분석 기록입니다.
                이 기록만을 근거로 장면 순서대로 사건 흐름을 정리하세요.
                - 등장인물, 행동, 대화에서 확인되는 사건을 우선하세요.
                - 의상, 배경 소품, 카메라 묘사는 줄거리와 관련 없으면 제외하세요.
                - 확인되지 않는 인물 이름, 관계, 동기, 사건의 인과관계를 만들어내지 마세요.
                - 서로 다른 사건을 억지로 연결하지 마세요.
                - 불확실한 장면은 '확인 불가'로 표시하세요.
                - 장면 번호를 유지한 짧은 항목으로 작성하세요.
                - 전체 출력은 %d자 이내로 작성하세요.

                [선정된 장면의 분석 기록]
                %s
                """.formatted(MAX_STORY_OUTLINE_CHARACTERS, input);
        String outline = sanitize(requestScript(prompt));
        return outline.length() <= MAX_STORY_OUTLINE_CHARACTERS
                ? outline : outline.substring(0, MAX_STORY_OUTLINE_CHARACTERS);
    }

    private String trimAtSentenceBoundary(String script, int limit) {
        if (script.length() <= limit) {
            return script;
        }
        String prefix = script.substring(0, limit);
        int boundary = Math.max(prefix.lastIndexOf('.'), Math.max(prefix.lastIndexOf('!'), prefix.lastIndexOf('?')));
        if (boundary >= limit / 2) {
            return prefix.substring(0, boundary + 1).trim();
        }
        int space = prefix.lastIndexOf(' ');
        return (space >= limit / 2 ? prefix.substring(0, space) : prefix).trim();
    }

    private String collectEvidence(List<ChatFileChunk> chunks, long start, long end, int limit) {
        if (end <= start) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (ChatFileChunk chunk : chunks) {
            if (chunk.getStartMillis() >= end || chunk.getEndMillis() <= start) {
                continue;
            }
            String content = chunk.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String line = "[" + (chunk.getStartMillis() / 1000) + "~"
                    + (chunk.getEndMillis() / 1000) + "초] " + content.strip() + "\n";
            int remaining = limit - result.length();
            if (remaining <= 0) {
                break;
            }
            result.append(line, 0, Math.min(line.length(), remaining));
        }
        return result.toString().trim();
    }

    private String requestScript(String prompt) {
        var response = chatModelProvider.chatOnce(
                new ChatModelRequest(
                        ChatModelType.TEXT,
                        List.of(
                                new ChatModelMessage("system",
                                        "실제 영상 분석 내용에 근거한 사실만 사용하여 짧고 정확한 한국어 내레이션을 작성합니다."),
                                new ChatModelMessage("user", prompt)
                        ),
                        List.of()
                )
        );
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("AI가 나레이션 대본을 생성하지 못했습니다.");
        }
        return response.content().trim();
    }

    private String sanitize(String script) {
        return script.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?m)^```[^\\n]*\\n?|^```$", "")
                .replaceAll("(?m)^\\s*(?:나레이션|내레이션|대본)\\s*:\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safe(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "정보 없음";
        }
        String normalized = value.strip();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private void validatePlan(DramaEditPlan plan) {
        if (plan == null || plan.segments() == null || plan.segments().isEmpty()) {
            throw new IllegalArgumentException("나레이션 대본을 만들 편집 구간이 없습니다.");
        }
    }
}
