package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.video.model.VideoFrame;
import com.agent.aiagent.domain.video.model.VideoFrameAnalysis;
import com.agent.aiagent.domain.video.model.VideoTranscript;
import com.agent.aiagent.domain.video.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFileAnalysisService {

    private static final String VIDEO_EXTENSION =
            "mp4";

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
    private final ChatFileStatusService chatFileStatusService;
    private final FileAnalysisProgressService fileAnalysisProgressService;
    private final FileAnalysisCancellationManager cancellationManager;

    public void analyze(
            String fileId
    ) {
        try {
            cancellationManager.checkCancelled(
                    fileId
            );

            chatFileStatusService.markAnalyzing(
                    fileId
            );

            fileAnalysisProgressService.running(
                    fileId,
                    "file_analysis",
                    "파일 분석 중..."
            );

            ChatFile chatFile =
                    chatFileRepository.findById(
                                    fileId
                            )
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "분석할 파일을 찾을 수 없습니다: "
                                                    + fileId
                                    )
                            );

            if (
                    isExtractableDocument(
                            chatFile.getExtension()
                    )
            ) {
                analyzeDocument(
                        chatFile
                );
            }

            if (
                    isVideo(
                            chatFile.getExtension()
                    )
            ) {
                analyzeVideo(
                        chatFile
                );
            }

            cancellationManager.checkCancelled(
                    fileId
            );

            chatFileStatusService.markCompleted(
                    fileId
            );

            fileAnalysisProgressService.completed(
                    fileId,
                    "file_analysis",
                    "파일 분석 완료"
            );

            log.info(
                    "파일 분석 완료. fileId={}, extension={}",
                    chatFile.getId(),
                    chatFile.getExtension()
            );
        } catch (FileAnalysisCancelledException exception) {
            log.info(
                    "파일 분석 중단. fileId={}",
                    fileId
            );

            throw exception;
        } catch (RuntimeException exception) {
            try {
                chatFileStatusService.markFailed(
                        fileId
                );
            } catch (Exception statusException) {
                log.error(
                        "파일 분석 실패 상태 저장 중 오류가 발생했습니다. fileId={}",
                        fileId,
                        statusException
                );
            }

            fileAnalysisProgressService.failed(
                    fileId,
                    "file_analysis",
                    "파일 분석 실패"
            );

            log.error(
                    "파일 분석 실패. fileId={}",
                    fileId,
                    exception
            );

            throw exception;
        }
    }

    private void analyzeDocument(
            ChatFile chatFile
    ) {
        String fileId =
                chatFile.getId();

        cancellationManager.checkCancelled(
                fileId
        );

        fileAnalysisProgressService.running(
                fileId,
                "document_index",
                "문서 내용 분석 중..."
        );

        chatFileChunkService.saveChunks(
                chatFile
        );

        cancellationManager.checkCancelled(
                fileId
        );

        fileAnalysisProgressService.completed(
                fileId,
                "document_index",
                "문서 내용 분석 완료"
        );

        log.info(
                "문서 분석 완료. fileId={}, extension={}",
                fileId,
                chatFile.getExtension()
        );
    }

    private void analyzeVideo(
            ChatFile chatFile
    ) {
        String fileId =
                chatFile.getId();

        cancellationManager.checkCancelled(
                fileId
        );

        analyzeVideoAudio(
                chatFile
        );

        cancellationManager.checkCancelled(
                fileId
        );

        analyzeVideoFrames(
                chatFile
        );
    }

    private void analyzeVideoAudio(
            ChatFile chatFile
    ) {
        String fileId =
                chatFile.getId();

        Path audioPath =
                null;

        try {
            cancellationManager.checkCancelled(
                    fileId
            );

            fileAnalysisProgressService.running(
                    fileId,
                    "audio_extract",
                    "영상에서 오디오 추출 중..."
            );

            audioPath =
                    videoAudioExtractor.extract(
                            fileId,
                            Path.of(
                                    chatFile.getStoredPath()
                            )
                    );

            cancellationManager.checkCancelled(
                    fileId
            );

            fileAnalysisProgressService.completed(
                    fileId,
                    "audio_extract",
                    "오디오 추출 완료"
            );

            fileAnalysisProgressService.running(
                    fileId,
                    "transcription",
                    "음성을 텍스트로 변환 중..."
            );

            VideoTranscript transcript =
                    whisperTranscriber.transcribe(
                            fileId,
                            audioPath
                    );

            cancellationManager.checkCancelled(
                    fileId
            );

            fileAnalysisProgressService.completed(
                    fileId,
                    "transcription",
                    "음성 인식 완료"
            );

            fileAnalysisProgressService.running(
                    fileId,
                    "transcript_index",
                    "영상 자막 인덱싱 중..."
            );

            chatFileChunkService.saveVideoTranscriptChunks(
                    chatFile,
                    transcript
            );

            cancellationManager.checkCancelled(
                    fileId
            );

            fileAnalysisProgressService.completed(
                    fileId,
                    "transcript_index",
                    "영상 자막 인덱싱 완료"
            );

            fileAnalysisProgressService.running(
                    fileId,
                    "video_summary",
                    "영상 내용 요약 중..."
            );

            String summary =
                    videoSummaryService.summarize(
                            transcript
                    );

            cancellationManager.checkCancelled(
                    fileId
            );

            chatFile.updateSummary(
                    summary
            );

            chatFileRepository.save(
                    chatFile
            );

            fileAnalysisProgressService.completed(
                    fileId,
                    "video_summary",
                    "영상 내용 요약 완료"
            );

            log.info(
                    "영상 음성 분석 완료. fileId={}, language={}, segmentCount={}, summaryLength={}",
                    fileId,
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
    }

    private void analyzeVideoFrames(
            ChatFile chatFile
    ) {
        String fileId =
                chatFile.getId();

        List<VideoFrame> frames =
                List.of();

        try {
            cancellationManager.checkCancelled(
                    fileId
            );

            fileAnalysisProgressService.running(
                    fileId,
                    "frame_extract",
                    "영상 프레임 추출 중..."
            );

            frames =
                    videoFrameExtractor.extract(
                            fileId,
                            Path.of(
                                    chatFile.getStoredPath()
                            )
                    );

            cancellationManager.checkCancelled(
                    fileId
            );

            fileAnalysisProgressService.completed(
                    fileId,
                    "frame_extract",
                    "영상 프레임 추출 완료"
            );

            log.info(
                    "영상 프레임 추출 확인. fileId={}, frameCount={}, firstFrame={}",
                    fileId,
                    frames.size(),
                    frames.isEmpty()
                            ? null
                            : frames.getFirst().path()
            );

            fileAnalysisProgressService.running(
                    fileId,
                    "frame_filter",
                    "중복 프레임 정리 중..."
            );

            List<VideoFrame> filteredFrames =
                    videoFrameDeduplicator.filter(
                            frames
                    );

            cancellationManager.checkCancelled(
                    fileId
            );

            fileAnalysisProgressService.completed(
                    fileId,
                    "frame_filter",
                    "중복 프레임 정리 완료"
            );

            log.info(
                    "영상 Vision 분석 대상 프레임 확정. fileId={}, originalCount={}, filteredCount={}, removedCount={}",
                    fileId,
                    frames.size(),
                    filteredFrames.size(),
                    frames.size()
                            - filteredFrames.size()
            );

            fileAnalysisProgressService.running(
                    fileId,
                    "vision_analysis",
                    "영상 장면 분석 중..."
            );

            List<VideoFrameAnalysis> frameAnalyses =
                    videoFrameAnalyzer.analyze(
                            fileId,
                            filteredFrames
                    );

            cancellationManager.checkCancelled(
                    fileId
            );

            chatFileChunkService.saveVideoFrameAnalysisChunks(
                    chatFile,
                    frameAnalyses
            );

            cancellationManager.checkCancelled(
                    fileId
            );

            fileAnalysisProgressService.completed(
                    fileId,
                    "vision_analysis",
                    "영상 장면 분석 완료"
            );
        } finally {
            videoFrameExtractor.cleanup(
                    frames
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
}