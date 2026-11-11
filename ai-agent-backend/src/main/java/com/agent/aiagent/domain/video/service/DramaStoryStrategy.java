package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaSummaryMode;
import com.agent.aiagent.provider.chat.ModelProviderType;
import java.util.List;

public interface DramaStoryStrategy {
    ModelProviderType providerType();
    DramaEditPlan plan(List<String> episodeFileIds, DramaSummaryMode mode, long targetDurationMillis);
}
