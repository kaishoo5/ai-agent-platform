package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.dto.ChatFileResponse;
import com.agent.aiagent.domain.file.dto.ChatFileUploadResponse;
import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.video.model.VideoFrame;
import com.agent.aiagent.domain.video.model.VideoFrameAnalysis;
import com.agent.aiagent.domain.video.model.VideoTranscript;
import com.agent.aiagent.domain.video.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFileService {

    private static final long MAX_FILE_SIZE =
            10L * 1024L * 1024L;

    private static final long MAX_VIDEO_FILE_SIZE =
            4L * 1024L * 1024L * 1024L;

    private static final String VIDEO_EXTENSION =
            "mp4";

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(
                    "txt",
                    "md",
                    "java",
                    "js",
                    "ts",
                    "tsx",
                    "json",
                    "sql",
                    "xml",
                    "yaml",
                    "yml",
                    "properties",
                    "pdf",
                    "docx",
                    "xlsx",
                    "png",
                    "jpg",
                    "jpeg",
                    "gif",
                    "webp",
                    VIDEO_EXTENSION
            );

    private static final Set<String> EXTRACTABLE_DOCUMENT_EXTENSIONS =
            Set.of(
                    "txt",
                    "md",
                    "java",
                    "js",
                    "ts",
                    "tsx",
                    "json",
                    "sql",
                    "xml",
                    "yaml",
                    "yml",
                    "properties",
                    "pdf",
                    "docx",
                    "xlsx"
            );

    private final ChatFileRepository chatFileRepository;
    private final ChatFileChunkService chatFileChunkService;
    private final VideoAudioExtractor videoAudioExtractor;
    private final WhisperTranscriber whisperTranscriber;
    private final VideoSummaryService videoSummaryService;
    private final VideoFrameExtractor videoFrameExtractor;
    private final VideoFrameAnalyzer videoFrameAnalyzer;
    private final VideoFrameDeduplicator videoFrameDeduplicator;

    @Value("${app.file.upload-dir}")
    private String uploadDirectory;

    @Transactional
    public ChatFileUploadResponse upload(
            String roomId,
            MultipartFile file
    ) {
        validateRoomId(
                roomId
        );

        validateFile(
                file
        );

        String originalName =
                StringUtils.cleanPath(
                        file.getOriginalFilename()
                );

        String extension =
                getExtension(
                        originalName
                );

        String storedName =
                UUID.randomUUID()
                        + "."
                        + extension;

        Path roomDirectory =
                Path.of(
                                uploadDirectory,
                                roomId
                        )
                        .toAbsolutePath()
                        .normalize();

        Path targetPath =
                roomDirectory
                        .resolve(
                                storedName
                        )
                        .normalize();

        if (!targetPath.startsWith(roomDirectory)) {
            throw new IllegalArgumentException(
                    "유효하지 않은 파일 경로입니다."
            );
        }

        try {
            Files.createDirectories(
                    roomDirectory
            );

            Files.copy(
                    file.getInputStream(),
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "파일 저장 중 오류가 발생했습니다.",
                    exception
            );
        }

        ChatFile chatFile =
                new ChatFile(
                        roomId,
                        originalName,
                        storedName,
                        targetPath.toString(),
                        file.getContentType(),
                        extension,
                        file.getSize()
                );

        ChatFile savedFile;

        try {
            savedFile =
                    chatFileRepository.saveAndFlush(
                            chatFile
                    );

            if (
                    isExtractableDocument(
                            savedFile.getExtension()
                    )
            ) {
                chatFileChunkService.saveChunks(
                        savedFile
                );
            }

            if (isVideo(savedFile.getExtension())) {
                Path audioPath = null;

                try {
                    audioPath =
                            videoAudioExtractor.extract(
                                    Path.of(savedFile.getStoredPath())
                            );

                    VideoTranscript transcript =
                            whisperTranscriber.transcribe(
                                    audioPath
                            );

                    chatFileChunkService.saveVideoTranscriptChunks(
                            savedFile,
                            transcript
                    );

                    String summary =
                            videoSummaryService.summarize(
                                    transcript
                            );

                    savedFile.updateSummary(
                            summary
                    );

                    log.info(
                            "영상 음성 분석 완료. fileId={}, language={}, segmentCount={}, summaryLength={}",
                            savedFile.getId(),
                            transcript.language(),
                            transcript.segments().size(),
                            summary != null
                                    ? summary.length()
                                    : 0
                    );
                } finally {
                    if (audioPath != null) {
                        try {
                            Files.deleteIfExists(
                                    audioPath
                            );
                        } catch (IOException exception) {
                            log.warn(
                                    "영상 임시 오디오 파일 삭제 실패. path={}",
                                    audioPath,
                                    exception
                            );
                        }
                    }
                }

                List<VideoFrame> frames =
                        List.of();

                try {
                    frames =
                            videoFrameExtractor.extract(
                                    Path.of(savedFile.getStoredPath())
                            );

                    log.info(
                            "영상 프레임 추출 확인. fileId={}, frameCount={}, firstFrame={}",
                            savedFile.getId(),
                            frames.size(),
                            frames.isEmpty()
                                    ? null
                                    : frames.getFirst().path()
                    );

                    List<VideoFrame> filteredFrames =
                            videoFrameDeduplicator.filter(
                                    frames
                            );

                    log.info(
                            "영상 Vision 분석 대상 프레임 확정. fileId={}, originalCount={}, filteredCount={}, removedCount={}",
                            savedFile.getId(),
                            frames.size(),
                            filteredFrames.size(),
                            frames.size() - filteredFrames.size()
                    );

                    List<VideoFrameAnalysis> frameAnalyses =
                            videoFrameAnalyzer.analyze(
                                    filteredFrames
                            );

                    chatFileChunkService.saveVideoFrameAnalysisChunks(
                            savedFile,
                            frameAnalyses
                    );
                } finally {
                    videoFrameExtractor.cleanup(
                            frames
                    );
                }
            }
        } catch (RuntimeException exception) {
            deleteStoredFile(
                    targetPath
            );

            throw exception;
        }

        return ChatFileUploadResponse.from(
                savedFile
        );
    }

    private void validateFile(
            MultipartFile file
    ) {
        if (
                file == null
                        || file.isEmpty()
        ) {
            throw new IllegalArgumentException(
                    "업로드할 파일이 없습니다."
            );
        }

        String originalName =
                StringUtils.cleanPath(
                        file.getOriginalFilename()
                );

        if (!StringUtils.hasText(originalName)) {
            throw new IllegalArgumentException(
                    "파일명이 없습니다."
            );
        }

        String extension =
                getExtension(
                        originalName
                );

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "지원하지 않는 파일 형식입니다: "
                            + extension
            );
        }

        long maxFileSize =
                isVideo(
                        extension
                )
                        ? MAX_VIDEO_FILE_SIZE
                        : MAX_FILE_SIZE;

        if (file.getSize() > maxFileSize) {
            if (isVideo(extension)) {
                throw new IllegalArgumentException(
                        "영상 파일 크기는 200MB를 초과할 수 없습니다."
                );
            }

            throw new IllegalArgumentException(
                    "파일 크기는 10MB를 초과할 수 없습니다."
            );
        }
    }

    private boolean isVideo(
            String extension
    ) {
        return VIDEO_EXTENSION.equals(
                extension
        );
    }

    private boolean isExtractableDocument(
            String extension
    ) {
        return EXTRACTABLE_DOCUMENT_EXTENSIONS.contains(
                extension
        );
    }

    private String getExtension(
            String filename
    ) {
        int extensionIndex =
                filename.lastIndexOf(
                        "."
                );

        if (
                extensionIndex < 0
                        || extensionIndex
                        == filename.length() - 1
        ) {
            throw new IllegalArgumentException(
                    "파일 확장자가 없습니다."
            );
        }

        return filename
                .substring(
                        extensionIndex + 1
                )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private void deleteStoredFile(
            Path path
    ) {
        try {
            Files.deleteIfExists(
                    path
            );
        } catch (IOException ignored) {
        }
    }

    @Transactional(readOnly = true)
    public List<ChatFileResponse> findAllByRoomId(
            String roomId
    ) {
        validateRoomId(
                roomId
        );

        return chatFileRepository
                .findAllByRoomIdOrderByCreatedAtAsc(
                        roomId
                )
                .stream()
                .map(
                        ChatFileResponse::from
                )
                .toList();
    }

    @Transactional
    public void delete(
            String roomId,
            String fileId
    ) {
        validateRoomId(
                roomId
        );

        if (!StringUtils.hasText(fileId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "파일 ID가 없습니다."
            );
        }

        ChatFile chatFile =
                chatFileRepository
                        .findByIdAndRoomId(
                                fileId,
                                roomId
                        )
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "파일을 찾을 수 없습니다."
                                )
                        );

        Path storedPath =
                Path.of(
                                chatFile.getStoredPath()
                        )
                        .toAbsolutePath()
                        .normalize();

        deleteStoredFileOrThrow(
                storedPath
        );

        chatFileRepository.delete(
                chatFile
        );
    }

    private void deleteStoredFileOrThrow(
            Path path
    ) {
        try {
            Files.deleteIfExists(
                    path
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "저장된 파일을 삭제하지 못했습니다.",
                    exception
            );
        }
    }

    private void validateRoomId(
            String roomId
    ) {
        if (!StringUtils.hasText(roomId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "채팅방 ID가 없습니다."
            );
        }
    }

    @Transactional(readOnly = true)
    public List<ChatFile> findFiles(
            String roomId,
            List<String> fileIds
    ) {
        validateRoomId(
                roomId
        );

        if (
                fileIds == null
                        || fileIds.isEmpty()
        ) {
            return List.of();
        }

        List<ChatFile> foundFiles =
                chatFileRepository.findAllById(
                        fileIds
                );

        return fileIds
                .stream()
                .map(fileId ->
                        foundFiles
                                .stream()
                                .filter(file ->
                                        fileId.equals(
                                                file.getId()
                                        )
                                )
                                .findFirst()
                                .orElseThrow(() ->
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "첨부파일을 찾을 수 없습니다: "
                                                        + fileId
                                        )
                                )
                )
                .peek(file -> {
                    if (
                            !roomId.equals(
                                    file.getRoomId()
                            )
                    ) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "현재 채팅방의 첨부파일이 아닙니다."
                        );
                    }
                })
                .toList();
    }
}