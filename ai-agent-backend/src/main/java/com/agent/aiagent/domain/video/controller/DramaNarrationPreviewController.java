package com.agent.aiagent.domain.video.controller;

import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaEditSegment;
import com.agent.aiagent.domain.video.model.DramaEpisodeAnalysis;
import com.agent.aiagent.domain.video.model.DramaSummaryMode;
import com.agent.aiagent.domain.video.service.DramaAiStoryPlanner;
import com.agent.aiagent.domain.video.service.DramaEpisodeAnalyzer;
import com.agent.aiagent.domain.video.service.DramaNarrationScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;

/**
 * 내레이션 대본만 미리보기: TTS 및 FFmpeg를 호출하지 않는다.
 * episodeFileIds는 회차 순서대로 전달한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/videos/drama/narration")
@RequiredArgsConstructor
public class DramaNarrationPreviewController {

    private final DramaEpisodeAnalyzer dramaEpisodeAnalyzer;
    private final DramaAiStoryPlanner dramaAiStoryPlanner;
    private final DramaNarrationScriptService dramaNarrationScriptService;

    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody PreviewRequest request) {
        if (request == null || request.episodeFileIds() == null
                || request.episodeFileIds().isEmpty() || request.episodeFileIds().size() > 20) {
            throw new IllegalArgumentException("episodeFileIds는 1~20개여야 합니다.");
        }
        if (request.episodeFileIds().stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(request.episodeFileIds()).size() != request.episodeFileIds().size()) {
            throw new IllegalArgumentException("fileId가 비어 있거나 중복되었습니다.");
        }

        long durationSeconds = request.durationSeconds() == null ? 300L : request.durationSeconds();
        if (durationSeconds < 30 || durationSeconds > 7200) {
            throw new IllegalArgumentException("durationSeconds는 30~7200 사이여야 합니다.");
        }

        log.info("드라마 내레이션 대본 미리보기 시작. episodes={}, durationSeconds={}",
                request.episodeFileIds().size(), durationSeconds);

        List<DramaEpisodeAnalysis> episodes = dramaEpisodeAnalyzer.analyzeEpisodes(request.episodeFileIds());
        DramaEditPlan plan = dramaAiStoryPlanner.plan(
                episodes, DramaSummaryMode.NARRATION, Math.multiplyExact(durationSeconds, 1000L));
        List<String> scripts = dramaNarrationScriptService.generateScenes(plan);

        if (scripts.size() != plan.segments().size()) {
            throw new IllegalStateException("장면 수와 대본 수가 일치하지 않습니다.");
        }

        List<PreviewScene> scenes = java.util.stream.IntStream.range(0, scripts.size())
                .mapToObj(i -> {
                    DramaEditSegment segment = plan.segments().get(i);
                    return new PreviewScene(
                            i + 1,
                            segment.episodeNumber(),
                            segment.fileId(),
                            segment.startMillis(),
                            segment.endMillis(),
                            segment.reason(),
                            scripts.get(i)
                    );
                }).toList();

        log.info("드라마 내레이션 대본 미리보기 완료. scenes={}", scenes.size());
        return new PreviewResponse(plan.targetDurationMillis(), plan.plannedDurationMillis(), scenes);
    }

    public record PreviewRequest(List<String> episodeFileIds, Long durationSeconds) {
    }

    public record PreviewScene(
            int sceneNumber,
            int episodeNumber,
            String fileId,
            long startMillis,
            long endMillis,
            String reason,
            String script
    ) {
    }

    public record PreviewResponse(
            long targetDurationMillis,
            long plannedDurationMillis,
            List<PreviewScene> scenes
    ) {
    }
}
