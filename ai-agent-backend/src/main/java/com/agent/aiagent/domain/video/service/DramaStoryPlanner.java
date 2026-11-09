package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class DramaStoryPlanner {

    /**
     * 장면의 끝을 임의로 잘라 목표 시간을 맞추지 않는다.
     * 회차별 예산 안에서 온전한 후보 구간의 조합을 고르고, 남은 시간은 다른 회차의
     * 미선택 후보로 보충한다. 출력은 항상 회차 및 원본 시간순이다.
     */
    public DramaEditPlan plan(
            List<DramaEpisodeAnalysis> episodes,
            DramaSummaryMode mode,
            long targetDurationMillis
    ) {
        if (episodes == null || episodes.isEmpty()) {
            throw new IllegalArgumentException("분석된 드라마 회차가 없습니다.");
        }
        if (mode == null) {
            throw new IllegalArgumentException("요약 모드가 없습니다.");
        }
        if (targetDurationMillis <= 0) {
            throw new IllegalArgumentException("목표 영상 길이는 0보다 커야 합니다.");
        }

        List<DramaEpisodeAnalysis> orderedEpisodes = episodes.stream()
                .sorted(Comparator.comparingInt(DramaEpisodeAnalysis::episodeNumber))
                .toList();

        Set<Integer> episodeNumbers = new HashSet<>();
        List<List<VideoHighlightSegment>> candidatesByEpisode = new ArrayList<>();
        long totalAvailableMillis = 0;
        for (DramaEpisodeAnalysis episode : orderedEpisodes) {
            if (episode.episodeNumber() < 1 || !episodeNumbers.add(episode.episodeNumber())) {
                throw new IllegalArgumentException("회차 번호가 잘못되었거나 중복되었습니다.");
            }
            if (episode.sourceDurationMillis() <= 0) {
                throw new IllegalArgumentException("영상 길이가 올바르지 않습니다. episode=" + episode.episodeNumber());
            }
            List<VideoHighlightSegment> candidates = normalize(
                    episode.highlightCandidates(), episode.sourceDurationMillis());
            candidatesByEpisode.add(candidates);
            for (VideoHighlightSegment candidate : candidates) {
                totalAvailableMillis = Math.addExact(totalAvailableMillis,
                        candidate.endMillis() - candidate.startMillis());
            }
        }

        if (totalAvailableMillis == 0) {
            throw new IllegalStateException("편집에 사용할 하이라이트 구간이 없습니다.");
        }

        long effectiveTarget = Math.min(targetDurationMillis, totalAvailableMillis);
        long[] budgets = allocateBudgets(candidatesByEpisode, effectiveTarget);
        List<Set<Integer>> chosenIndices = new ArrayList<>();
        long plannedDuration = 0;

        for (int i = 0; i < candidatesByEpisode.size(); i++) {
            Set<Integer> chosen = selectWholeSegments(candidatesByEpisode.get(i), budgets[i]);
            chosenIndices.add(chosen);
            for (int index : chosen) {
                VideoHighlightSegment segment = candidatesByEpisode.get(i).get(index);
                plannedDuration += segment.endMillis() - segment.startMillis();
            }
        }

        // 예산 안에서 선택하지 못한 잔여 시간은 다른 회차의 온전한 구간으로 보충한다.
        // 길이가 긴 후보를 먼저 추가하면 짧은 조각을 여러 개 만드는 일을 피할 수 있다.
        List<SegmentRef> remainingCandidates = new ArrayList<>();
        for (int i = 0; i < candidatesByEpisode.size(); i++) {
            for (int j = 0; j < candidatesByEpisode.get(i).size(); j++) {
                if (!chosenIndices.get(i).contains(j)) {
                    VideoHighlightSegment segment = candidatesByEpisode.get(i).get(j);
                    remainingCandidates.add(new SegmentRef(i, j,
                            segment.endMillis() - segment.startMillis()));
                }
            }
        }
        remainingCandidates.sort(Comparator.comparingLong(SegmentRef::durationMillis).reversed());
        for (SegmentRef ref : remainingCandidates) {
            if (ref.durationMillis() <= effectiveTarget - plannedDuration) {
                chosenIndices.get(ref.episodeIndex()).add(ref.segmentIndex());
                plannedDuration += ref.durationMillis();
            }
        }

        List<DramaEditSegment> selected = new ArrayList<>();
        for (int i = 0; i < orderedEpisodes.size(); i++) {
            DramaEpisodeAnalysis episode = orderedEpisodes.get(i);
            List<VideoHighlightSegment> candidates = candidatesByEpisode.get(i);
            for (int j = 0; j < candidates.size(); j++) {
                if (!chosenIndices.get(i).contains(j)) {
                    continue;
                }
                VideoHighlightSegment candidate = candidates.get(j);
                selected.add(new DramaEditSegment(
                        episode.episodeNumber(), episode.fileId(),
                        candidate.startMillis(), candidate.endMillis(), candidate.reason()
                ));
            }
        }

        log.info("드라마 편집 계획 완료. mode={}, episodeCount={}, segmentCount={}, targetMillis={}, plannedMillis={}, differenceMillis={}",
                mode, orderedEpisodes.size(), selected.size(), targetDurationMillis,
                plannedDuration, targetDurationMillis - plannedDuration);

        return new DramaEditPlan(mode, targetDurationMillis, plannedDuration, selected);
    }

    /**
     * 밀리초 단위의 무거운 DP 대신 1초 단위 근사 DP를 사용한다.
     * DP 상태의 시간은 올림하여 계산하므로 실제 선택 길이가 예산을 초과하지 않는다.
     */
    private Set<Integer> selectWholeSegments(List<VideoHighlightSegment> candidates, long budgetMillis) {
        if (candidates.isEmpty() || budgetMillis <= 0) {
            return new HashSet<>();
        }
        // 지나치게 긴 입력에서도 메모리를 제한한다. 일반적인 20분 요약은 1초 해상도.
        long unit = Math.max(1000L, (budgetMillis + 19999L) / 20000L);
        int capacity = (int) (budgetMillis / unit);
        if (capacity == 0) {
            return new HashSet<>();
        }

        // 각 상태에 선택 인덱스 집합을 저장하지 않고 경로 노드를 연결한다.
        long[] bestActual = new long[capacity + 1];
        Arrays.fill(bestActual, -1L);
        PathNode[] paths = new PathNode[capacity + 1];
        bestActual[0] = 0;

        for (int i = 0; i < candidates.size(); i++) {
            VideoHighlightSegment candidate = candidates.get(i);
            long duration = candidate.endMillis() - candidate.startMillis();
            long rounded = (duration + unit - 1) / unit;
            if (rounded > capacity) {
                continue;
            }
            int weight = (int) rounded;
            for (int c = capacity; c >= weight; c--) {
                if (bestActual[c - weight] < 0) {
                    continue;
                }
                long nextActual = bestActual[c - weight] + duration;
                if (nextActual <= budgetMillis && nextActual > bestActual[c]) {
                    bestActual[c] = nextActual;
                    paths[c] = new PathNode(i, paths[c - weight]);
                }
            }
        }

        int bestCapacity = 0;
        for (int c = 1; c <= capacity; c++) {
            if (bestActual[c] > bestActual[bestCapacity]) {
                bestCapacity = c;
            }
        }
        Set<Integer> result = new HashSet<>();
        for (PathNode node = paths[bestCapacity]; node != null; node = node.previous()) {
            result.add(node.index());
        }
        return result;
    }

    private List<VideoHighlightSegment> normalize(
            List<VideoHighlightSegment> candidates,
            long sourceDurationMillis
    ) {
        if (candidates == null) {
            return List.of();
        }
        List<VideoHighlightSegment> sorted = candidates.stream()
                .filter(segment -> segment != null && segment.endMillis() > segment.startMillis())
                .map(segment -> new VideoHighlightSegment(
                        Math.max(0, segment.startMillis()),
                        Math.min(sourceDurationMillis, segment.endMillis()),
                        segment.reason()))
                .filter(segment -> segment.endMillis() > segment.startMillis())
                .sorted(Comparator.comparingLong(VideoHighlightSegment::startMillis))
                .toList();

        List<VideoHighlightSegment> result = new ArrayList<>();
        long lastEnd = 0;
        for (VideoHighlightSegment segment : sorted) {
            long start = Math.max(segment.startMillis(), lastEnd);
            if (start < segment.endMillis()) {
                result.add(new VideoHighlightSegment(start, segment.endMillis(), segment.reason()));
                lastEnd = segment.endMillis();
            }
        }
        return result;
    }

    private long[] allocateBudgets(List<List<VideoHighlightSegment>> groups, long target) {
        int count = groups.size();
        long[] available = new long[count];
        long[] budgets = new long[count];
        for (int i = 0; i < count; i++) {
            for (VideoHighlightSegment segment : groups.get(i)) {
                available[i] += segment.endMillis() - segment.startMillis();
            }
        }

        long remaining = target;
        while (remaining > 0) {
            int active = 0;
            for (int i = 0; i < count; i++) {
                if (budgets[i] < available[i]) {
                    active++;
                }
            }
            if (active == 0) {
                break;
            }
            long share = Math.max(1, remaining / active);
            long allocated = 0;
            for (int i = 0; i < count && remaining > 0; i++) {
                long room = available[i] - budgets[i];
                long amount = Math.min(Math.min(share, room), remaining);
                budgets[i] += amount;
                remaining -= amount;
                allocated += amount;
            }
            if (allocated == 0) {
                break;
            }
        }
        return budgets;
    }

    private record SegmentRef(int episodeIndex, int segmentIndex, long durationMillis) { }

    private record PathNode(int index, PathNode previous) { }
}
