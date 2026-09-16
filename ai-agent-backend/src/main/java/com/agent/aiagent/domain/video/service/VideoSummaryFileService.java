package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class VideoSummaryFileService {

    private static final String SUMMARY_FILE_SUFFIX =
            "_AI_Summary.mp4";

    private final ChatFileRepository chatFileRepository;

    public Resource getSummaryVideo(
            String fileId
    ) {
        ChatFile chatFile =
                findChatFile(
                        fileId
                );

        Path summaryPath =
                resolveSummaryPath(
                        chatFile
                );

        if (
                !Files.exists(summaryPath)
                        || !Files.isRegularFile(summaryPath)
        ) {
            throw new IllegalArgumentException(
                    "생성된 요약 영상을 찾을 수 없습니다. fileId="
                            + fileId
            );
        }

        try {
            Resource resource =
                    new UrlResource(
                            summaryPath.toUri()
                    );

            if (!resource.exists()) {
                throw new IllegalArgumentException(
                        "생성된 요약 영상을 찾을 수 없습니다. fileId="
                                + fileId
                );
            }

            return resource;
        } catch (MalformedURLException exception) {
            throw new IllegalStateException(
                    "요약 영상 파일을 읽을 수 없습니다.",
                    exception
            );
        }
    }

    public String getSummaryFileName(
            String fileId
    ) {
        ChatFile chatFile =
                findChatFile(
                        fileId
                );

        return createDisplayFileName(
                chatFile
        );
    }

    private ChatFile findChatFile(
            String fileId
    ) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException(
                    "fileId가 없습니다."
            );
        }

        return chatFileRepository.findById(
                        fileId
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "영상 파일 정보를 찾을 수 없습니다. fileId="
                                        + fileId
                        )
                );
    }

    private String createDisplayFileName(
            ChatFile chatFile
    ) {
        String originalName =
                chatFile.getOriginalName();

        if (!StringUtils.hasText(originalName)) {
            return "AI_Video_Summary.mp4";
        }

        String fileName =
                Path.of(
                                originalName
                        )
                        .getFileName()
                        .toString();

        int extensionIndex =
                fileName.lastIndexOf(
                        "."
                );

        String baseName =
                extensionIndex > 0
                        ? fileName.substring(
                        0,
                        extensionIndex
                )
                        : fileName;

        if (!StringUtils.hasText(baseName)) {
            return "AI_Video_Summary.mp4";
        }

        return baseName
                + SUMMARY_FILE_SUFFIX;
    }

    private Path resolveSummaryPath(
            ChatFile chatFile
    ) {
        if (!StringUtils.hasText(
                chatFile.getStoredPath()
        )) {
            throw new IllegalStateException(
                    "원본 영상 파일 경로가 없습니다."
            );
        }

        Path originalPath =
                Path.of(
                                chatFile.getStoredPath()
                        )
                        .toAbsolutePath()
                        .normalize();

        Path parent =
                originalPath.getParent();

        if (parent == null) {
            throw new IllegalStateException(
                    "영상 저장 디렉터리를 확인할 수 없습니다."
            );
        }

        String fileName =
                originalPath
                        .getFileName()
                        .toString();

        int extensionIndex =
                fileName.lastIndexOf(
                        "."
                );

        String baseName =
                extensionIndex > 0
                        ? fileName.substring(
                        0,
                        extensionIndex
                )
                        : fileName;

        Path summaryPath =
                parent.resolve(
                                baseName
                                        + "_summary.mp4"
                        )
                        .toAbsolutePath()
                        .normalize();

        if (!summaryPath.startsWith(parent)) {
            throw new IllegalStateException(
                    "유효하지 않은 요약 영상 경로입니다."
            );
        }

        return summaryPath;
    }
}