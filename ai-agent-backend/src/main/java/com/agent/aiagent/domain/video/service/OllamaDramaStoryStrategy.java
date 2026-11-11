package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaSummaryMode;
import com.agent.aiagent.provider.chat.ModelProviderType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OllamaDramaStoryStrategy implements DramaStoryStrategy {
    private final DramaEpisodeAnalyzer dramaEpisodeAnalyzer;
    private final DramaAiStoryPlanner dramaAiStoryPlanner;

    @Override
    public ModelProviderType providerType() {
        return ModelProviderType.OLLAMA;
    }

    @Override
    public DramaEditPlan plan(List<String> episodeFileIds, DramaSummaryMode mode, long targetDurationMillis) {
        return dramaAiStoryPlanner.plan(
                dramaEpisodeAnalyzer.analyzeEpisodes(episodeFileIds), mode, targetDurationMillis);
    }
}
