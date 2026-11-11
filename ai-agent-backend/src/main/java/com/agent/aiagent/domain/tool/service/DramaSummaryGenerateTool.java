package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaSummaryMode;
import com.agent.aiagent.domain.video.service.DramaStoryStrategy;
import com.agent.aiagent.provider.chat.ModelProviderType;
import com.agent.aiagent.settings.service.ModelSettingsService;
import com.agent.aiagent.domain.video.service.DramaNarrationVideoGenerator;
import com.agent.aiagent.domain.video.service.DramaStoryVideoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class DramaSummaryGenerateTool implements AgentTool {

    private static final ToolSpecification SPECIFICATION = new ToolSpecification(
            "drama_summary_generate",
            "여러 회차의 드라마 영상을 순서대로 분석하고 줄거리 요약 영상을 생성합니다. "
                    + "episodeFileIds는 1회부터 순서대로 전달합니다. "
                    + "STORY 모드는 주요 장면을 이어 붙이고 NARRATION 모드는 AI 한국어 해설을 합성합니다.",
            Map.of(
                    "episodeFileIds", new ToolParameter("array", "1회부터 순서대로 나열한 영상 fileId 목록입니다.", true),
                    "durationSeconds", new ToolParameter("number", "최종 요약 영상 목표 길이(초), 기본 1200초입니다.", false),
                    "mode", new ToolParameter("string", "STORY 또는 NARRATION 모드입니다.", false)
            )
    );

    private static final Pattern EPISODE_PATTERN = Pattern.compile("(?i)(?:S(\\d{1,2})[ ._-]*)?E(\\d{1,3})(?!\\d)");

    private final ChatFileRepository chatFileRepository;
    private final ModelSettingsService modelSettingsService;
    private final List<DramaStoryStrategy> dramaStoryStrategies;
    private final DramaStoryVideoGenerator dramaStoryVideoGenerator;
    private final DramaNarrationVideoGenerator dramaNarrationVideoGenerator;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    private List<String> orderEpisodeFileIds(List<String> fileIds) {
        List<EpisodeFile> entries = new ArrayList<>();
        for (int i = 0; i < fileIds.size(); i++) {
            String fileId = fileIds.get(i);
            ChatFile file = chatFileRepository.findById(fileId)
                    .orElseThrow(() -> new IllegalArgumentException("영상 파일이 없습니다. fileId=" + fileId));
            String name = file.getOriginalName();
            Matcher matcher = EPISODE_PATTERN.matcher(name == null ? "" : name);
            Integer season = null;
            Integer episode = null;
            if (matcher.find()) {
                season = matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1));
                episode = Integer.parseInt(matcher.group(2));
            }
            entries.add(new EpisodeFile(fileId, name, season, episode, i));
        }

        if (entries.stream().anyMatch(e -> e.episode() == null)) {
            log.warn("회차를 인식할 수 없는 파일이 있어 입력 순서를 유지합니다. files={}",
                    entries.stream().map(EpisodeFile::name).toList());
            return List.copyOf(fileIds);
        }

        Set<String> episodeKeys = new HashSet<>();
        for (EpisodeFile entry : entries) {
            if (!episodeKeys.add(entry.season() + ":" + entry.episode())) {
                throw new IllegalArgumentException("중복된 시즌/회차가 있습니다: " + entry.name());
            }
        }

        entries.sort(Comparator.comparingInt(EpisodeFile::season)
                .thenComparingInt(EpisodeFile::episode));
        log.info("드라마 회차 정렬 완료. files={}",
                entries.stream().map(EpisodeFile::name).toList());
        return entries.stream().map(EpisodeFile::fileId).toList();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            if (arguments == null || !(arguments.get("episodeFileIds") instanceof List<?> rawIds)) {
                return ToolResult.failure("episodeFileIds 배열이 필요합니다.");
            }

            List<String> fileIds = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (Object value : rawIds) {
                if (!(value instanceof String id) || id.isBlank() || !seen.add(id)) {
                    return ToolResult.failure("fileId는 중복되지 않는 문자열이어야 합니다.");
                }
                fileIds.add(id);
            }

            if (fileIds.isEmpty() || fileIds.size() > 20) {
                return ToolResult.failure("회차 수는 1~20개여야 합니다.");
            }

            String modeText = String.valueOf(
                    arguments.getOrDefault("mode", "STORY")
            ).trim().toUpperCase();

            DramaSummaryMode mode;

            try {
                mode = DramaSummaryMode.valueOf(modeText);
            } catch (IllegalArgumentException exception) {
                return ToolResult.failure("지원하지 않는 모드입니다: " + modeText);
            }

            Object rawDuration = arguments.getOrDefault("durationSeconds", 1200);
            long durationSeconds;

            try {
                durationSeconds = Long.parseLong(rawDuration.toString());
            } catch (NumberFormatException exception) {
                return ToolResult.failure("durationSeconds는 정수여야 합니다.");
            }

            if (durationSeconds < 30 || durationSeconds > 7200) {
                return ToolResult.failure("요약 길이는 30~7200초여야 합니다.");
            }

            fileIds = orderEpisodeFileIds(fileIds);

            log.info(
                    "드라마 요약 시작. episodes={}, durationSeconds={}, mode={}",
                    fileIds.size(),
                    durationSeconds,
                    mode
            );

            ModelProviderType providerType = ModelProviderType.fromModelId(modelSettingsService.getTextModel());
            DramaStoryStrategy strategy = dramaStoryStrategies.stream()
                    .filter(candidate -> candidate.providerType() == providerType)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("드라마 전략이 없습니다. provider=" + providerType));

            log.info("드라마 전략 선택. provider={}, strategy={}",
                    providerType, strategy.getClass().getSimpleName());

            DramaEditPlan plan = strategy.plan(
                    fileIds,
                    mode,
                    Math.multiplyExact(durationSeconds, 1000L)
            );

            Path output = switch (mode) {
                case STORY -> dramaStoryVideoGenerator.generate(plan);
                case NARRATION -> dramaNarrationVideoGenerator.generate(plan);
            };

            log.info("드라마 요약 완료. output={}", output);

            return ToolResult.success(
                    "드라마 " + mode.name() + " 요약 영상 생성 완료: " + output.getFileName(),
                    Map.of(
                            "generatedFile", output.getFileName().toString(),
                            "sourceFileId", fileIds.getFirst(),
                            "plannedDurationMillis", plan.plannedDurationMillis(),
                            "mode", mode.name()
                    )
            );

        } catch (Exception exception) {
            log.error("드라마 요약 생성 실패", exception);
            return ToolResult.failure(
                    "드라마 요약 생성 실패: " + exception.getMessage()
            );
        }
    }

    private record EpisodeFile(String fileId, String name, Integer season,
                               Integer episode, int originalIndex) {
    }
}
