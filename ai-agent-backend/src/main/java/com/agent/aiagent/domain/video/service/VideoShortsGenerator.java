package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoShortsGenerator {

    private final ChatFileRepository chatFileRepository;
    private final VideoShortsHighlightSelector videoShortsHighlightSelector;
    private final VideoShortsSubtitleGenerator videoShortsSubtitleGenerator;
    private final VideoShortsClipGenerator videoShortsClipGenerator;

    public Path generate(
            String fileId,
            long targetDurationMillis
    ) {
        List<Path> outputPaths =
                generate(
                        fileId,
                        targetDurationMillis,
                        1
                );

        return outputPaths.get(
                0
        );
    }

    public List<Path> generate(
            String fileId,
            long targetDurationMillis,
            int count
    ) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException(
                    "fileId가 없습니다."
            );
        }

        if (targetDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "targetDurationMillis는 0보다 커야 합니다."
            );
        }

        if (count <= 0) {
            throw new IllegalArgumentException(
                    "count는 0보다 커야 합니다."
            );
        }

        ChatFile chatFile =
                chatFileRepository.findById(
                                fileId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "영상 파일 정보를 찾을 수 없습니다. fileId="
                                                + fileId
                                )
                        );

        if (!StringUtils.hasText(
                chatFile.getStoredPath()
        )) {
            throw new IllegalStateException(
                    "저장된 영상 파일 경로가 없습니다. fileId="
                            + fileId
            );
        }

        Path videoPath =
                Path.of(
                                chatFile.getStoredPath()
                        )
                        .toAbsolutePath()
                        .normalize();

        log.info(
                "AI 쇼츠 영상 생성 시작. fileId={}, fileName={}, targetDurationMillis={}, count={}, videoPath={}",
                fileId,
                chatFile.getOriginalName(),
                targetDurationMillis,
                count,
                videoPath
        );

        List<VideoHighlightSegment> candidates =
                videoShortsHighlightSelector.selectCandidates(
                        fileId
                );

        log.info(
                "AI 쇼츠 전체 하이라이트 후보 조회 완료. fileId={}, candidateCount={}",
                fileId,
                candidates.size()
        );

        List<VideoHighlightSegment> selectedSegments =
                new ArrayList<>();

        List<Path> outputPaths =
                new ArrayList<>();

        for (
                int index = 0;
                index < count;
                index++
        ) {
            log.info(
                    "AI 쇼츠 개별 생성 시작. fileId={}, currentIndex={}, count={}",
                    fileId,
                    index + 1,
                    count
            );

            VideoHighlightSegment segment =
                    videoShortsHighlightSelector.selectFromCandidates(
                            fileId,
                            targetDurationMillis,
                            candidates,
                            selectedSegments
                    );

            selectedSegments.add(
                    segment
            );

            log.info(
                    "AI 쇼츠 구간 선정 결과. fileId={}, currentIndex={}, startMillis={}, endMillis={}, durationMillis={}, reason={}",
                    fileId,
                    index + 1,
                    segment.startMillis(),
                    segment.endMillis(),
                    segment.endMillis()
                            - segment.startMillis(),
                    segment.reason()
            );

            Path subtitlePath =
                    null;

            try {
                subtitlePath =
                        videoShortsSubtitleGenerator.generate(
                                fileId,
                                videoPath,
                                segment.startMillis(),
                                segment.endMillis()
                        );

                log.info(
                        "AI 쇼츠 자막 준비 완료. fileId={}, currentIndex={}, subtitlePath={}, hasSubtitle={}",
                        fileId,
                        index + 1,
                        subtitlePath,
                        subtitlePath != null
                );

                Path outputPath =
                        videoShortsClipGenerator.generate(
                                videoPath,
                                segment,
                                subtitlePath,
                                chatFile.getOriginalName()
                        );

                outputPaths.add(
                        outputPath
                );

                log.info(
                        "AI 쇼츠 개별 영상 생성 완료. fileId={}, currentIndex={}, outputPath={}",
                        fileId,
                        index + 1,
                        outputPath
                );
            } finally {
                videoShortsSubtitleGenerator.cleanup(
                        subtitlePath
                );
            }
        }

        log.info(
                "AI 쇼츠 영상 생성 전체 완료. fileId={}, requestedCount={}, generatedCount={}, outputPaths={}",
                fileId,
                count,
                outputPaths.size(),
                outputPaths
        );

        return outputPaths;
    }
}