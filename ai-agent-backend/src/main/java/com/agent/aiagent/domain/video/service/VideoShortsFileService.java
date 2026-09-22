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
public class VideoShortsFileService {

    private final ChatFileRepository chatFileRepository;

    public Resource getShortsVideo(
            String fileId,
            String generatedFileName
    ) {
        ChatFile chatFile =
                findChatFile(
                        fileId
                );

        Path shortsPath =
                resolveShortsPath(
                        chatFile,
                        generatedFileName
                );

        if (
                !Files.exists(shortsPath)
                        || !Files.isRegularFile(shortsPath)
        ) {
            throw new IllegalArgumentException(
                    "생성된 쇼츠 영상을 찾을 수 없습니다. fileId="
                            + fileId
                            + ", fileName="
                            + generatedFileName
            );
        }

        try {
            Resource resource =
                    new UrlResource(
                            shortsPath.toUri()
                    );

            if (!resource.exists()) {
                throw new IllegalArgumentException(
                        "생성된 쇼츠 영상을 찾을 수 없습니다. fileId="
                                + fileId
                                + ", fileName="
                                + generatedFileName
                );
            }

            return resource;
        } catch (MalformedURLException exception) {
            throw new IllegalStateException(
                    "쇼츠 영상 파일을 읽을 수 없습니다.",
                    exception
            );
        }
    }

    public String getShortsFileName(
            String fileId,
            String generatedFileName
    ) {
        ChatFile chatFile =
                findChatFile(
                        fileId
                );

        Path shortsPath =
                resolveShortsPath(
                        chatFile,
                        generatedFileName
                );

        return shortsPath
                .getFileName()
                .toString();
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

    private Path resolveShortsPath(
            ChatFile chatFile,
            String generatedFileName
    ) {
        if (!StringUtils.hasText(
                generatedFileName
        )) {
            throw new IllegalArgumentException(
                    "쇼츠 영상 파일명이 없습니다."
            );
        }

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

        String safeFileName =
                Path.of(
                                generatedFileName
                        )
                        .getFileName()
                        .toString();

        if (
                !generatedFileName.equals(
                        safeFileName
                )
        ) {
            throw new IllegalArgumentException(
                    "유효하지 않은 쇼츠 영상 파일명입니다."
            );
        }

        String originalFileName =
                chatFile.getOriginalName();

        if (!StringUtils.hasText(
                originalFileName
        )) {
            throw new IllegalStateException(
                    "원본 영상 파일명이 없습니다."
            );
        }

        String safeOriginalFileName =
                Path.of(
                                originalFileName
                        )
                        .getFileName()
                        .toString();

        int extensionIndex =
                safeOriginalFileName.lastIndexOf(
                        "."
                );

        String baseName =
                extensionIndex > 0
                        ? safeOriginalFileName.substring(
                        0,
                        extensionIndex
                )
                        : safeOriginalFileName;

        if (
                !safeFileName.startsWith(
                        baseName
                                + "_shorts_"
                )
                        || !safeFileName.endsWith(
                        ".mp4"
                )
        ) {
            throw new IllegalArgumentException(
                    "유효하지 않은 쇼츠 영상 파일명입니다."
            );
        }

        Path shortsPath =
                parent.resolve(
                                safeFileName
                        )
                        .toAbsolutePath()
                        .normalize();

        if (!shortsPath.startsWith(parent)) {
            throw new IllegalStateException(
                    "유효하지 않은 쇼츠 영상 경로입니다."
            );
        }

        return shortsPath;
    }
}