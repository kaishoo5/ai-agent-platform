package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.video.model.VideoHighlightSelection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoSummaryGenerator {

    private final ChatFileRepository chatFileRepository;
    private final VideoHighlightSelector videoHighlightSelector;
    private final VideoClipGenerator videoClipGenerator;

    public Path generate(
            String fileId,
            long targetDurationMillis
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
                "AI 영상 요약본 생성 시작. fileId={}, fileName={}, targetDurationMillis={}, videoPath={}",
                fileId,
                chatFile.getOriginalName(),
                targetDurationMillis,
                videoPath
        );

        VideoHighlightSelection selection =
                videoHighlightSelector.select(
                        fileId,
                        targetDurationMillis
                );

        if (
                selection.segments() == null
                        || selection.segments().isEmpty()
        ) {
            throw new IllegalStateException(
                    "AI가 영상 하이라이트 구간을 선정하지 못했습니다."
            );
        }

        long selectedDurationMillis =
                selection.segments()
                        .stream()
                        .mapToLong(segment ->
                                segment.endMillis()
                                        - segment.startMillis()
                        )
                        .sum();

        log.info(
                "AI 영상 하이라이트 선정 결과. fileId={}, segmentCount={}, selectedDurationMillis={}",
                fileId,
                selection.segments().size(),
                selectedDurationMillis
        );

        selection.segments()
                .forEach(segment ->
                        log.info(
                                "AI 영상 하이라이트 구간. fileId={}, startMillis={}, endMillis={}, durationMillis={}, reason={}",
                                fileId,
                                segment.startMillis(),
                                segment.endMillis(),
                                segment.endMillis()
                                        - segment.startMillis(),
                                segment.reason()
                        )
                );

        Path outputPath =
                videoClipGenerator.generate(
                        videoPath,
                        selection
                );

        log.info(
                "AI 영상 요약본 생성 완료. fileId={}, outputPath={}",
                fileId,
                outputPath
        );

        return outputPath;
    }
}