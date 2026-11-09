package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.video.model.DramaEpisodeAnalysis;
import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaEpisodeAnalyzer {

    private final ChatFileRepository chatFileRepository;
    private final ChatFileChunkRepository chatFileChunkRepository;
    private final VideoHighlightSelector videoHighlightSelector;

    /**
     * 전달된 fileId 순서를 회차 순서로 사용한다.
     * 메모리/VRAM 사용량을 억제하기 위해 회차별로 순차 분석한다.
     * 분석 결과 영속 캐시는 후속 단계에서 연결한다.
     */
    public List<DramaEpisodeAnalysis> analyzeEpisodes(List<String> episodeFileIds) {
        if (episodeFileIds == null || episodeFileIds.isEmpty()) {
            throw new IllegalArgumentException("분석할 드라마 영상이 없습니다.");
        }

        if (episodeFileIds.size() > 20) {
            throw new IllegalArgumentException("한 번에 분석할 수 있는 회차는 최대 20개입니다.");
        }

        Set<String> uniqueIds = new HashSet<>();
        for (String fileId : episodeFileIds) {
            if (!StringUtils.hasText(fileId) || !uniqueIds.add(fileId)) {
                throw new IllegalArgumentException("fileId가 비어 있거나 중복되었습니다.");
            }
        }

        List<DramaEpisodeAnalysis> results = new ArrayList<>();
        for (int i = 0; i < episodeFileIds.size(); i++) {
            results.add(analyzeEpisode(i + 1, episodeFileIds.get(i)));
        }
        return List.copyOf(results);
    }

    public DramaEpisodeAnalysis analyzeEpisode(int episodeNumber, String fileId) {
        if (episodeNumber < 1 || !StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException("회차 번호 또는 fileId가 올바르지 않습니다.");
        }

        ChatFile file = chatFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("영상 파일이 없습니다. fileId=" + fileId));

        if (!StringUtils.hasText(file.getStoredPath())) {
            throw new IllegalStateException("영상 저장 경로가 없습니다. fileId=" + fileId);
        }

        Path path = Path.of(file.getStoredPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("원본 영상 파일이 없습니다. path=" + path);
        }

        List<ChatFileChunk> chunks = chatFileChunkRepository.findAllByFileId(fileId);
        long sourceDurationMillis = chunks.stream()
                .filter(chunk -> chunk.getEndMillis() != null)
                .mapToLong(ChatFileChunk::getEndMillis)
                .max()
                .orElse(0L);

        if (sourceDurationMillis <= 0) {
            throw new IllegalStateException("영상 분석 청크가 없거나 타임스탬프가 없습니다. fileId=" + fileId);
        }

        log.info("드라마 회차 분석 시작. episode={}, fileId={}, fileName={}",
                episodeNumber, fileId, file.getOriginalName());

        List<VideoHighlightSegment> candidates = videoHighlightSelector.selectCandidates(fileId);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("하이라이트 후보가 없습니다. episode=" + episodeNumber);
        }

        log.info("드라마 회차 분석 완료. episode={}, fileId={}, candidateCount={}",
                episodeNumber, fileId, candidates.size());

        return new DramaEpisodeAnalysis(
                episodeNumber,
                fileId,
                file.getOriginalName(),
                sourceDurationMillis,
                candidates
        );
    }
}
