package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaSummaryMode;
import com.agent.aiagent.provider.chat.ModelProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiDramaStoryStrategy implements DramaStoryStrategy {

    private final DramaEpisodeAnalyzer dramaEpisodeAnalyzer;
    private final DramaAiStoryPlanner dramaAiStoryPlanner;
    private final GeminiDramaStoryPlanner geminiDramaStoryPlanner;

    @Override
    public ModelProviderType providerType() {
        return ModelProviderType.GEMINI;
    }

    @Override
    public DramaEditPlan plan(
            List<String> episodeFileIds,
            DramaSummaryMode mode,
            long targetDurationMillis
    ) {
        if (mode == DramaSummaryMode.STORY) {
            log.info(
                    "Gemini 통합 STORY 전략 실행. episodes={}, targetMillis={}",
                    episodeFileIds.size(),
                    targetDurationMillis
            );

            return geminiDramaStoryPlanner.plan(
                    episodeFileIds,
                    targetDurationMillis
            );
        }

        // NARRATION은 기존 경로 그대로 유지한다.
        return dramaAiStoryPlanner.plan(
                dramaEpisodeAnalyzer.analyzeEpisodes(episodeFileIds),
                mode,
                targetDurationMillis
        );
    }
}
