package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.service.AgentProgressReporter;
import com.agent.aiagent.domain.chat.service.ChatStreamingExecutor;
import com.agent.aiagent.domain.rag.model.ChatSource;
import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.video.model.VideoResult;
import com.agent.aiagent.domain.video.service.VideoSummaryFileService;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelRequestBuilder;
import com.agent.aiagent.provider.chat.ChatModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallingExecutor {

    private static final int MAX_TOOL_CALL_ROUNDS = 5;

    private static final String AI_EDIT_CODE_TOOL_NAME =
            "ai_edit_code";

    private static final String VIDEO_SUMMARY_GENERATE_TOOL_NAME =
            "video_summary_generate";

    private static final String VIDEO_SHORTS_GENERATE_TOOL_NAME =
            "video_shorts_generate";

    private static final long DEFAULT_VIDEO_SUMMARY_DURATION_SECONDS =
            180L;

    private static final long DEFAULT_VIDEO_SHORTS_DURATION_SECONDS =
            45L;

    private final ChatModelProvider chatModelProvider;
    private final ToolCallProcessor toolCallProcessor;
    private final ChatModelRequestBuilder chatModelRequestBuilder;
    private final ChatStreamingExecutor chatStreamingExecutor;
    private final VideoSummaryFileService videoSummaryFileService;

    public SseEmitter execute(
            ChatRequest request,
            ChatModelRequest chatModelRequest
    ) {
        return execute(
                request,
                chatModelRequest,
                List.of()
        );
    }

    public SseEmitter execute(
            ChatRequest request,
            ChatModelRequest chatModelRequest,
            List<ChatSource> sources
    ) {
        SseEmitter emitter =
                chatStreamingExecutor.createEmitter();

        return execute(
                emitter,
                request,
                chatModelRequest,
                sources,
                List.of(),
                null
        );
    }

    public SseEmitter execute(
            SseEmitter emitter,
            ChatRequest request,
            ChatModelRequest chatModelRequest,
            List<ChatSource> sources
    ) {
        return execute(
                emitter,
                request,
                chatModelRequest,
                sources,
                List.of(),
                null
        );
    }

    public SseEmitter execute(
            SseEmitter emitter,
            ChatRequest request,
            ChatModelRequest chatModelRequest,
            List<ChatSource> sources,
            List<String> documentFileIds,
            AgentProgressReporter progressReporter
    ) {
        List<ChatSource> safeSources =
                sources == null
                        ? List.of()
                        : List.copyOf(
                        sources
                );

        if (chatModelRequest.tools().isEmpty()) {
            return chatStreamingExecutor.execute(
                    emitter,
                    request,
                    chatModelRequest,
                    List.of(),
                    safeSources
            );
        }

        ChatModelRequest currentRequest =
                chatModelRequest;

        List<VideoResult> videoResults =
                new ArrayList<>();

        for (
                int round = 1;
                round <= MAX_TOOL_CALL_ROUNDS;
                round++
        ) {
            boolean firstRound =
                    round == 1;

            String progressCode =
                    firstRound
                            ? "request_analysis"
                            : "answer_generation";

            String runningMessage =
                    firstRound
                            ? "요청 분석 중..."
                            : "답변 생성 중...";

            String completedMessage =
                    firstRound
                            ? "요청 분석 완료"
                            : "답변 생성 완료";

            if (progressReporter != null) {
                progressReporter.running(
                        progressCode,
                        runningMessage
                );
            }

            ChatModelResponse response =
                    chatModelProvider.chatOnce(
                            currentRequest
                    );

            /*
             * 첫 번째 round는 도구 사용 여부를 판단하는 단계이므로
             * 완료 상태를 보여준다.
             *
             * 두 번째 이후 round는 최종 답변 생성 단계다.
             * 응답이 도착하면 곧바로 message SSE가 전송되므로
             * answer_generation completed를 굳이 전송하지 않는다.
             */
            if (
                    progressReporter != null
                            && firstRound
            ) {
                progressReporter.completed(
                        progressCode,
                        completedMessage
                );
            }

            if (!response.hasToolCalls()) {
                String content =
                        response.content();

                if (
                        content == null
                                || content.isBlank()
                ) {
                    log.warn(
                            "Tool Calling 최종 응답이 비어 있습니다. "
                                    + "스트리밍 응답으로 재시도합니다. round={}",
                            round
                    );

                    ChatModelRequest finalRequest =
                            new ChatModelRequest(
                                    currentRequest.modelType(),
                                    currentRequest.messages(),
                                    List.of()
                            );

                    if (progressReporter != null) {
                        progressReporter.running(
                                "answer_generation",
                                "답변 생성 중..."
                        );
                    }

                    return chatStreamingExecutor.execute(
                            emitter,
                            request,
                            finalRequest,
                            videoResults,
                            safeSources
                    );
                }

                log.debug(
                        "Tool Calling 종료. round={}, videoCount={}",
                        round,
                        videoResults.size()
                );

                return chatStreamingExecutor
                        .executeCompletedResponse(
                                emitter,
                                request,
                                content,
                                videoResults,
                                safeSources
                        );
            }

            /*
             * 두 번째 이후 round에서도 또 다른 Tool Call이 나온 경우
             * 답변 생성 단계가 끝난 것이 아니라 추가 도구 실행이
             * 필요한 상태이므로 completed 처리한다.
             */
            if (
                    progressReporter != null
                            && !firstRound
            ) {
                progressReporter.completed(
                        progressCode,
                        completedMessage
                );
            }

            log.info(
                    "Tool Calling 실행. round={}, toolCallCount={}, tools={}",
                    round,
                    response.toolCalls().size(),
                    response.toolCalls()
                            .stream()
                            .map(toolCall ->
                                    toolCall.name()
                            )
                            .toList()
            );

            if (progressReporter != null) {
                progressReporter.running(
                        "tool_execution",
                        "도구 실행 중..."
                );
            }

            ToolExecutionContext toolExecutionContext =
                    new ToolExecutionContext(
                            request.getRoomId(),
                            documentFileIds
                    );

            List<ToolResult> toolResults =
                    toolCallProcessor.execute(
                            response.toolCalls(),
                            toolExecutionContext
                    );

            if (progressReporter != null) {
                progressReporter.completed(
                        "tool_execution",
                        "도구 실행 완료"
                );
            }

            for (
                    int index = 0;
                    index < response.toolCalls().size();
                    index++
            ) {
                var toolCall =
                        response.toolCalls().get(
                                index
                        );

                ToolResult toolResult =
                        toolResults.get(
                                index
                        );

                if (!toolResult.success()) {
                    continue;
                }

                if (
                        VIDEO_SUMMARY_GENERATE_TOOL_NAME.equals(
                                toolCall.name()
                        )
                ) {
                    VideoResult videoResult =
                            createVideoSummaryResult(
                                    toolCall.arguments()
                            );

                    if (videoResult != null) {
                        videoResults.add(
                                videoResult
                        );
                    }

                    continue;
                }

                if (
                        VIDEO_SHORTS_GENERATE_TOOL_NAME.equals(
                                toolCall.name()
                        )
                ) {
                    videoResults.addAll(
                            createVideoShortsResults(
                                    toolCall.arguments(),
                                    toolResult
                            )
                    );
                }
            }

            currentRequest =
                    chatModelRequestBuilder.appendToolResults(
                            currentRequest,
                            response.toolCalls(),
                            toolResults
                    );

            boolean aiEditCodeExecuted =
                    response.toolCalls()
                            .stream()
                            .anyMatch(toolCall ->
                                    AI_EDIT_CODE_TOOL_NAME.equals(
                                            toolCall.name()
                                    )
                            );

            if (aiEditCodeExecuted) {
                log.info(
                        "AI Edit Code Tool 실행 후 Tool Calling 종료. round={}",
                        round
                );

                ChatModelRequest finalRequest =
                        new ChatModelRequest(
                                currentRequest.modelType(),
                                currentRequest.messages(),
                                List.of()
                        );

                if (progressReporter != null) {
                    progressReporter.running(
                            "answer_generation",
                            "답변 생성 중..."
                    );
                }

                return chatStreamingExecutor.execute(
                        emitter,
                        request,
                        finalRequest,
                        videoResults,
                        safeSources
                );
            }
        }

        log.warn(
                "Tool Calling 최대 반복 횟수에 도달했습니다. maxRounds={}",
                MAX_TOOL_CALL_ROUNDS
        );

        if (progressReporter != null) {
            progressReporter.running(
                    "answer_generation",
                    "답변 생성 중..."
            );
        }

        return chatStreamingExecutor.execute(
                emitter,
                request,
                currentRequest,
                videoResults,
                safeSources
        );
    }

    private VideoResult createVideoSummaryResult(
            Map<String, Object> arguments
    ) {
        String fileId =
                getStringArgument(
                        arguments,
                        "fileId"
                );

        long durationSeconds =
                getLongArgument(
                        arguments,
                        "durationSeconds",
                        DEFAULT_VIDEO_SUMMARY_DURATION_SECONDS
                );

        String fileName =
                videoSummaryFileService.getSummaryFileName(
                        fileId
                );

        String streamUrl =
                "/api/videos/summaries/"
                        + fileId;

        String downloadUrl =
                streamUrl
                        + "?download=true";

        return new VideoResult(
                fileId,
                fileName,
                durationSeconds,
                streamUrl,
                downloadUrl,
                "SUMMARY"
        );
    }

    private List<VideoResult> createVideoShortsResults(
            Map<String, Object> arguments,
            ToolResult toolResult
    ) {
        if (
                toolResult == null
                        || !toolResult.success()
        ) {
            return List.of();
        }

        String fileId =
                getStringArgument(
                        arguments,
                        "fileId"
                );

        if (fileId == null) {
            return List.of();
        }

        long durationSeconds =
                getLongArgument(
                        arguments,
                        "durationSeconds",
                        DEFAULT_VIDEO_SHORTS_DURATION_SECONDS
                );

        Map<String, Object> metadata =
                toolResult.metadata();

        if (
                metadata == null
                        || metadata.isEmpty()
        ) {
            log.warn(
                    "쇼츠 영상 결과 metadata가 없습니다. fileId={}",
                    fileId
            );

            return List.of();
        }

        Object generatedFilesValue =
                metadata.get(
                        "generatedFiles"
                );

        if (!(generatedFilesValue instanceof List<?> generatedFiles)) {
            log.warn(
                    "쇼츠 생성 파일 목록이 없습니다. fileId={}, metadata={}",
                    fileId,
                    metadata
            );

            return List.of();
        }

        List<VideoResult> results =
                new ArrayList<>();

        for (Object generatedFile : generatedFiles) {
            if (generatedFile == null) {
                continue;
            }

            String fileName =
                    generatedFile
                            .toString()
                            .trim();

            if (fileName.isBlank()) {
                continue;
            }

            String encodedFileName =
                    UriUtils.encodePathSegment(
                            fileName,
                            StandardCharsets.UTF_8
                    );

            String streamUrl =
                    "/api/videos/shorts/"
                            + fileId
                            + "/"
                            + encodedFileName;

            String downloadUrl =
                    streamUrl
                            + "?download=true";

            results.add(
                    new VideoResult(
                            fileId,
                            fileName,
                            durationSeconds,
                            streamUrl,
                            downloadUrl,
                            "SHORTS"
                    )
            );
        }

        log.info(
                "쇼츠 영상 결과 생성 완료. fileId={}, videoCount={}",
                fileId,
                results.size()
        );

        return List.copyOf(
                results
        );
    }

    private String getStringArgument(
            Map<String, Object> arguments,
            String name
    ) {
        if (
                arguments == null
                        || arguments.get(name) == null
        ) {
            return null;
        }

        String value =
                arguments.get(name)
                        .toString()
                        .trim();

        return value.isBlank()
                ? null
                : value;
    }

    private long getLongArgument(
            Map<String, Object> arguments,
            String name,
            long defaultValue
    ) {
        if (
                arguments == null
                        || arguments.get(name) == null
        ) {
            return defaultValue;
        }

        Object value =
                arguments.get(name);

        if (value instanceof Number number) {
            return number.longValue();
        }

        String normalizedValue =
                value.toString()
                        .trim();

        if (normalizedValue.isBlank()) {
            return defaultValue;
        }

        return Long.parseLong(
                normalizedValue
        );
    }
}