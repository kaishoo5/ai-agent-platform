package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaEpisodeAnalysis;
import com.agent.aiagent.domain.video.model.DramaSummaryMode;
import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 회차별 후보의 스토리 중요도를 LLM으로 평가한 뒤 기존 편집 계획 생성기를 재사용한다.
 * 현재 단계는 후보 reason 기반 평가이며 실제 영상 프레임이나 전체 대본을 읽지는 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaAiStoryPlanner {

    private static final int MAX_CANDIDATES_PER_REQUEST = 60;
    private static final int MAX_REASON_LENGTH = 180;

    private final ChatModelProvider chatModelProvider;
    private final DramaStoryPlanner dramaStoryPlanner;
    private final ObjectMapper objectMapper;
    private final ChatFileChunkRepository chatFileChunkRepository;

    public DramaEditPlan plan(
            List<DramaEpisodeAnalysis> episodes,
            DramaSummaryMode mode,
            long targetDurationMillis
    ) {
        if (episodes == null || episodes.isEmpty()) {
            throw new IllegalArgumentException("분석된 회차가 없습니다.");
        }

        List<DramaEpisodeAnalysis> rankedEpisodes = new ArrayList<>();
        long episodeTarget = Math.max(1L, targetDurationMillis / episodes.size());
        for (DramaEpisodeAnalysis episode : episodes.stream()
                .sorted(Comparator.comparingInt(DramaEpisodeAnalysis::episodeNumber)).toList()) {
            rankedEpisodes.add(rankEpisode(episode, episodeTarget));
        }
        return dramaStoryPlanner.plan(rankedEpisodes, mode, targetDurationMillis);
    }

    private DramaEpisodeAnalysis rankEpisode(DramaEpisodeAnalysis episode, long episodeTargetMillis) {
        List<VideoHighlightSegment> candidates = episode.highlightCandidates();
        if (candidates == null || candidates.size() < 2) {
            return episode;
        }

        // 후보가 너무 많으면 여러 번 호출하는 대신 기존 플래너로 안전하게 폴백한다.
        if (candidates.size() > MAX_CANDIDATES_PER_REQUEST) {
            log.warn("드라마 AI 평가 생략: 후보 수 초과. episode={}, count={}",
                    episode.episodeNumber(), candidates.size());
            return episode;
        }

        List<ChatFileChunk> chunks;
        try {
            chunks = chatFileChunkRepository.findAllByFileId(episode.fileId());
        } catch (Exception exception) {
            log.warn("드라마 후보 원문 조회 실패. episode={}, error={}",
                    episode.episodeNumber(), exception.toString());
            chunks = List.of();
        }

        StringBuilder candidateText = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            VideoHighlightSegment segment = candidates.get(i);
            String reason = segment.reason() == null ? "" : segment.reason();
            reason = reason.replace('\n', ' ').replace('\r', ' ');
            if (reason.length() > MAX_REASON_LENGTH) {
                reason = reason.substring(0, MAX_REASON_LENGTH);
            }
            candidateText.append(i + 1).append(" | ")
                    .append(segment.startMillis()).append("-")
                    .append(segment.endMillis()).append(" | ")
                    .append(reason).append('\n')
                    .append("근거 자막/화면 분석: ")
                    .append(buildEvidence(chunks, segment))
                    .append('\n');
        }

        String prompt = """
                다음은 드라마 한 회차의 하이라이트 후보입니다.
                줄거리 이해에 중요한 사건, 인물 관계 변화, 갈등, 반전, 사건의 원인과 결과를 우선하여
                가장 중요한 후보를 중요도 순으로 최대 12개까지 선택하세요.
                단순 식사, 이동, 일상 대화는 그 장면이 실제 갈등이나 사건의 원인/결과를 보여주지 않는다면 제외하세요.
                후보의 설명(reason)이 실제 자막/화면 분석과 일치하는지 검증하세요.
                자막에 없는 인물 관계나 사건을 추측하지 마세요.
                반드시 아래 후보 번호만 사용하고, 새로운 장면이나 시간을 만들지 마세요.
                사건 전개 순서는 후처리에서 복원하므로 중요도 높은 순서로 반환하세요.
                JSON 객체만 반환하세요. 예: {"candidateIds":[3,1,5]}

                후보 목록:
                %s
                """.formatted(candidateText);

        try {
            ChatModelResponse response = chatModelProvider.chatOnce(
                    new ChatModelRequest(
                            ChatModelType.TEXT,
                            List.of(
                                    new ChatModelMessage("system",
                                            "당신은 드라마 줄거리 요약 영상의 핵심 사건을 선정하는 편집자입니다."),
                                    new ChatModelMessage("user", prompt)
                            ),
                            List.of()
                    )
            );

            if (response == null || !StringUtils.hasText(response.content())) {
                return episode;
            }

            String json = response.content().replace("```json", "")
                    .replace("```JSON", "").replace("```", "").trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return episode;
            }

            CandidateSelection selection = objectMapper.readValue(
                    json.substring(start, end + 1), CandidateSelection.class);
            if (selection == null || selection.candidateIds() == null) {
                return episode;
            }

            Set<Integer> selectedIds = new HashSet<>();
            for (Integer id : selection.candidateIds()) {
                if (id != null && id >= 1 && id <= candidates.size()) {
                    selectedIds.add(id);
                }
            }
            if (selectedIds.isEmpty()) {
                return episode;
            }

            // 우선 선정된 후보를 보존하되, 회차 목표 분량에 미달하면
            // 제외된 후보를 시간순으로 보충한다. 기존 편집 계획의 분량 부족을 방지한다.
            List<VideoHighlightSegment> selected = new ArrayList<>();
            long selectedDuration = 0L;
            for (int i = 0; i < candidates.size(); i++) {
                if (selectedIds.contains(i + 1)) {
                    VideoHighlightSegment segment = candidates.get(i);
                    selected.add(segment);
                    selectedDuration += segment.endMillis() - segment.startMillis();
                }
            }
            if (selectedDuration < episodeTargetMillis) {
                for (int i = 0; i < candidates.size(); i++) {
                    if (selectedDuration >= episodeTargetMillis) {
                        break;
                    }
                    if (!selectedIds.contains(i + 1)) {
                        VideoHighlightSegment segment = candidates.get(i);
                        selected.add(segment);
                        selectedDuration += segment.endMillis() - segment.startMillis();
                    }
                }
            }
            selected.sort(Comparator.comparingLong(VideoHighlightSegment::startMillis));
            log.info("드라마 AI 장면 선정 완료. episode={}, before={}, after={}",
                    episode.episodeNumber(), candidates.size(), selected.size());

            return new DramaEpisodeAnalysis(
                    episode.episodeNumber(), episode.fileId(), episode.originalFileName(),
                    episode.sourceDurationMillis(), selected);
        } catch (Exception exception) {
            log.warn("드라마 AI 장면 선정 실패, 기존 후보로 진행. episode={}, error={}",
                    episode.episodeNumber(), exception.toString());
            return episode;
        }
    }


    /** 후보 구간과 실제로 겹치는 자막/화면 분석만 모델에 제공한다. */
    private String buildEvidence(List<ChatFileChunk> chunks, VideoHighlightSegment segment) {
        if (chunks == null || chunks.isEmpty()) {
            return "(원문 데이터 없음: 후보 설명만으로 판단)";
        }
        StringBuilder evidence = new StringBuilder();
        for (ChatFileChunk chunk : chunks) {
            if (chunk == null || chunk.getStartMillis() >= segment.endMillis()
                    || chunk.getEndMillis() <= segment.startMillis()) {
                continue;
            }
            String content = chunk.getContent();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            content = content.replace('\n', ' ').replace('\r', ' ').trim();
            int remaining = 900 - evidence.length();
            if (remaining <= 0) {
                break;
            }
            evidence.append(content, 0, Math.min(content.length(), remaining)).append(' ');
        }
        return evidence.isEmpty() ? "(겹치는 원문 없음)" : evidence.toString().trim();
    }

    public record CandidateSelection(List<Integer> candidateIds) {
    }
}
