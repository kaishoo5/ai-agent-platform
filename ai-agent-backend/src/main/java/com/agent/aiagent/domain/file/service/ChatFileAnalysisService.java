package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.gemini.service.GeminiFileService;
import com.agent.aiagent.domain.video.model.VideoFrame;
import com.agent.aiagent.domain.video.model.VideoFrameAnalysis;
import com.agent.aiagent.domain.video.model.VideoTranscript;
import com.agent.aiagent.domain.video.service.*;
import com.agent.aiagent.infra.gemini.GeminiFileClient;
import com.agent.aiagent.provider.chat.ModelProviderType;
import com.agent.aiagent.settings.service.ModelSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFileAnalysisService {

    private static final long GEMINI_SEGMENT_SECONDS = 600L;
    private static final long GEMINI_SEGMENT_INTERVAL_MILLIS = 20_000L;

    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of(
                    "mp4",
                    "mkv"
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
                    "xlsx",
                    "zip"
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
    private final VideoTranscriptSegmentService videoTranscriptSegmentService;
    private final ModelSettingsService modelSettingsService;
    private final GeminiFileClient geminiFileClient;
    private final GeminiFileService geminiFileService;
    private final ObjectMapper objectMapper;

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

        long startedAt =
                System.nanoTime();

        cancellationManager.checkCancelled(
                fileId
        );

        String visionModel = modelSettingsService.getVisionModel();
        boolean useGemini = "mp4".equalsIgnoreCase(chatFile.getExtension())
                && ModelProviderType.fromModelId(visionModel) == ModelProviderType.GEMINI;

        if (useGemini) {
            analyzeVideoWithGemini(chatFile);
            log.info("Gemini 영상 전체 분석 시간. fileId={}, elapsed={}ms",
                    fileId, elapsedMillis(startedAt));
            return;
        }

        analyzeVideoAudio(
                chatFile
        );

        cancellationManager.checkCancelled(
                fileId
        );

        analyzeVideoFrames(
                chatFile
        );

        log.info(
                "영상 전체 분석 시간. fileId={}, elapsed={}ms",
                fileId,
                elapsedMillis(
                        startedAt
                )
        );
    }

    private void analyzeVideoWithGemini(ChatFile chatFile) {
        String fileId = chatFile.getId();
        cancellationManager.checkCancelled(fileId);
        fileAnalysisProgressService.running(fileId, "gemini_upload", "Gemini 영상 업로드 중...");

        JsonNode uploaded = objectMapper.readTree(
                geminiFileClient.upload(Path.of(chatFile.getStoredPath()))
        ).path("file");
        String fileName = uploaded.path("name").asText("");
        String fileUri = uploaded.path("uri").asText("");
        if (fileName.isBlank() || fileUri.isBlank()) {
            throw new IllegalStateException("Gemini 파일 업로드 응답에 name 또는 uri가 없습니다.");
        }

        fileAnalysisProgressService.running(fileId, "gemini_processing", "Gemini 영상 처리 대기 중...");
        boolean active = false;
        for (int attempt = 0; attempt < 240; attempt++) {
            cancellationManager.checkCancelled(fileId);
            JsonNode state = objectMapper.readTree(geminiFileClient.getFile(fileName));
            String status = state.path("state").asText("");
            if (status.isBlank()) {
                status = state.path("file").path("state").asText("");
            }
            if ("ACTIVE".equalsIgnoreCase(status)) {
                active = true;
                break;
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Gemini 영상 처리 실패: " + state);
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Gemini 영상 처리 대기가 중단되었습니다.", exception);
            }
        }
        if (!active) {
            throw new IllegalStateException("Gemini 영상 처리 대기 시간이 초과되었습니다(최대 20분).");
        }

        cancellationManager.checkCancelled(fileId);
        fileAnalysisProgressService.running(fileId, "gemini_analysis", "Gemini 영상 분석 중...");
        long durationSeconds = probeVideoDurationSeconds(Path.of(chatFile.getStoredPath()));
        int segmentCount = Math.toIntExact(
                (durationSeconds + GEMINI_SEGMENT_SECONDS - 1) / GEMINI_SEGMENT_SECONDS
        );
        log.info("Gemini 구간 분석 시작. fileId={}, durationSeconds={}, segments={}",
                fileId, durationSeconds, segmentCount);

        for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
            cancellationManager.checkCancelled(fileId);
            long startSeconds = segmentIndex * GEMINI_SEGMENT_SECONDS;
            long endSeconds = Math.min(durationSeconds, startSeconds + GEMINI_SEGMENT_SECONDS);
            fileAnalysisProgressService.running(fileId, "gemini_analysis",
                    "Gemini 영상 구간 분석 중... (" + (segmentIndex + 1) + "/" + segmentCount + ")");

            String prompt = "이 영상의 지정된 구간(" + formatVideoTime(startSeconds)
                    + " ~ " + formatVideoTime(endSeconds) + ")만 분석해줘. "
                    + "실제 화면과 음성에서 확인되는 사실을 바탕으로 주요 진행, 출연자, "
                    + "대화 내용, 곡과 순위, 중요한 사건을 발생 순서대로 한국어로 상세히 기록해줘. "
                    + "구간 내부의 주요 장면마다 영상 전체 기준 HH:MM:SS 시각을 적어줘. "
                    + "구간 밖 내용은 다루지 말고, 확인되지 않은 대사나 사건은 추측하지 마. "
                    + "간단한 총평보다 구체적인 장면 기록을 우선해줘.";

            String content = geminiFileService.analyzeSegment(
                    fileUri, prompt, startSeconds, endSeconds
            ).content();
            cancellationManager.checkCancelled(fileId);
            chatFileChunkService.saveGeminiVideoSegmentChunk(
                    chatFile, segmentIndex, startSeconds * 1000L,
                    endSeconds * 1000L, content
            );
            log.info("Gemini 구간 분석 완료. fileId={}, segment={}/{}, contentLength={}",
                    fileId, segmentIndex + 1, segmentCount, content.length());

            if (segmentIndex + 1 < segmentCount) {
                try {
                    Thread.sleep(GEMINI_SEGMENT_INTERVAL_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Gemini 구간 분석 대기가 중단되었습니다.", exception);
                }
            }
        }
        fileAnalysisProgressService.completed(fileId, "gemini_analysis", "Gemini 구간별 영상 분석 완료");
        log.info("Gemini 영상 구간별 분석 및 청크 저장 완료. fileId={}, segmentCount={}",
                fileId, segmentCount);
    }

    private long probeVideoDurationSeconds(Path videoPath) {
        try {
            Process process = new ProcessBuilder(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", videoPath.toString()
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("ffprobe 영상 길이 조회 시간이 초과되었습니다.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffprobe 영상 길이 조회 실패: " + output);
            }
            long seconds = (long) Math.ceil(Double.parseDouble(output));
            if (seconds <= 0) {
                throw new IllegalStateException("영상 길이가 올바르지 않습니다: " + output);
            }
            return seconds;
        } catch (IOException exception) {
            throw new IllegalStateException("ffprobe 실행에 실패했습니다. ffprobe 설치 및 PATH를 확인하세요.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("영상 길이 조회가 중단되었습니다.", exception);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("ffprobe 영상 길이 응답을 해석할 수 없습니다.", exception);
        }
    }

    private String formatVideoTime(long seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600,
                (seconds % 3600) / 60, seconds % 60);
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

            long audioExtractStartedAt =
                    System.nanoTime();

            audioPath =
                    videoAudioExtractor.extract(
                            fileId,
                            Path.of(
                                    chatFile.getStoredPath()
                            )
                    );

            log.info(
                    "영상 분석 성능. fileId={}, step=audio_extract, elapsed={}ms",
                    fileId,
                    elapsedMillis(
                            audioExtractStartedAt
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

            long transcriptionStartedAt =
                    System.nanoTime();

            VideoTranscript transcript =
                    whisperTranscriber.transcribe(
                            fileId,
                            audioPath
                    );

            log.info(
                    "영상 분석 성능. fileId={}, step=transcription, elapsed={}ms, segmentCount={}",
                    fileId,
                    elapsedMillis(
                            transcriptionStartedAt
                    ),
                    transcript.segments().size()
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

            long transcriptSegmentSaveStartedAt =
                    System.nanoTime();

            videoTranscriptSegmentService.save(
                    chatFile,
                    transcript
            );

            log.info(
                    "영상 분석 성능. fileId={}, step=transcript_segment_save, elapsed={}ms",
                    fileId,
                    elapsedMillis(
                            transcriptSegmentSaveStartedAt
                    )
            );

            cancellationManager.checkCancelled(
                    fileId
            );

            long transcriptChunkIndexStartedAt =
                    System.nanoTime();

            chatFileChunkService.saveVideoTranscriptChunks(
                    chatFile,
                    transcript
            );

            log.info(
                    "영상 분석 성능. fileId={}, step=transcript_chunk_index, elapsed={}ms",
                    fileId,
                    elapsedMillis(
                            transcriptChunkIndexStartedAt
                    )
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

            long videoSummaryStartedAt =
                    System.nanoTime();

            String summary =
                    videoSummaryService.summarize(
                            transcript
                    );

            log.info(
                    "영상 분석 성능. fileId={}, step=video_summary, elapsed={}ms",
                    fileId,
                    elapsedMillis(
                            videoSummaryStartedAt
                    )
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

            long frameExtractStartedAt =
                    System.nanoTime();

            frames =
                    videoFrameExtractor.extract(
                            fileId,
                            Path.of(
                                    chatFile.getStoredPath()
                            )
                    );

            log.info(
                    "영상 분석 성능. fileId={}, step=frame_extract, elapsed={}ms, frameCount={}",
                    fileId,
                    elapsedMillis(
                            frameExtractStartedAt
                    ),
                    frames.size()
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

            long frameFilterStartedAt =
                    System.nanoTime();

            List<VideoFrame> filteredFrames =
                    videoFrameDeduplicator.filter(
                            frames
                    );

            log.info(
                    "영상 분석 성능. fileId={}, step=frame_filter, elapsed={}ms, originalCount={}, filteredCount={}",
                    fileId,
                    elapsedMillis(
                            frameFilterStartedAt
                    ),
                    frames.size(),
                    filteredFrames.size()
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

            long visionAnalysisStartedAt =
                    System.nanoTime();

            List<VideoFrameAnalysis> frameAnalyses =
                    videoFrameAnalyzer.analyze(
                            fileId,
                            filteredFrames
                    );

            log.info(
                    "영상 분석 성능. fileId={}, step=vision_analysis, elapsed={}ms, frameCount={}",
                    fileId,
                    elapsedMillis(
                            visionAnalysisStartedAt
                    ),
                    frameAnalyses.size()
            );

            cancellationManager.checkCancelled(
                    fileId
            );

            long visionChunkIndexStartedAt =
                    System.nanoTime();

            chatFileChunkService.saveVideoFrameAnalysisChunks(
                    chatFile,
                    frameAnalyses
            );

            log.info(
                    "영상 분석 성능. fileId={}, step=vision_chunk_index, elapsed={}ms, analysisCount={}",
                    fileId,
                    elapsedMillis(
                            visionChunkIndexStartedAt
                    ),
                    frameAnalyses.size()
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

    private long elapsedMillis(
            long startedAt
    ) {
        return (
                System.nanoTime()
                        - startedAt
        ) / 1_000_000L;
    }

    private boolean isVideo(
            String extension
    ) {
        return VIDEO_EXTENSIONS.contains(
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