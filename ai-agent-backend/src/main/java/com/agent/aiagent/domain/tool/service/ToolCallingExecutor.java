package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.service.ChatStreamingExecutor;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.video.model.VideoSummaryResult;
import com.agent.aiagent.domain.video.service.VideoSummaryFileService;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelRequestBuilder;
import com.agent.aiagent.provider.chat.ChatModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    private static final long DEFAULT_VIDEO_SUMMARY_DURATION_SECONDS =
            180L;

    private final ChatModelProvider chatModelProvider;
    private final ToolCallProcessor toolCallProcessor;
    private final ChatModelRequestBuilder chatModelRequestBuilder;
    private final ChatStreamingExecutor chatStreamingExecutor;
    private final VideoSummaryFileService videoSummaryFileService;

    public SseEmitter execute(
            ChatRequest request,
            ChatModelRequest chatModelRequest
    ) {

        if (chatModelRequest.tools().isEmpty()) {
            return chatStreamingExecutor.execute(
                    request,
                    chatModelRequest
            );
        }

        ChatModelRequest currentRequest =
                chatModelRequest;

        VideoSummaryResult videoSummaryResult =
                null;

        for (
                int round = 1;
                round <= MAX_TOOL_CALL_ROUNDS;
                round++
        ) {
            ChatModelResponse response =
                    chatModelProvider.chatOnce(
                            currentRequest
                    );

            if (!response.hasToolCalls()) {
                log.debug(
                        "Tool Calling 종료. round={}",
                        round
                );

                return chatStreamingExecutor.execute(
                        request,
                        currentRequest,
                        videoSummaryResult
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

            List<ToolResult> toolResults =
                    toolCallProcessor.execute(
                            response.toolCalls()
                    );

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

                if (
                        VIDEO_SUMMARY_GENERATE_TOOL_NAME.equals(
                                toolCall.name()
                        )
                                && toolResult.success()
                ) {
                    videoSummaryResult =
                            createVideoSummaryResult(
                                    toolCall.arguments()
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

                return chatStreamingExecutor.execute(
                        request,
                        finalRequest,
                        videoSummaryResult
                );
            }
        }

        log.warn(
                "Tool Calling 최대 반복 횟수에 도달했습니다. maxRounds={}",
                MAX_TOOL_CALL_ROUNDS
        );

        return chatStreamingExecutor.execute(
                request,
                currentRequest,
                videoSummaryResult
        );
    }

    private VideoSummaryResult createVideoSummaryResult(
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

        return new VideoSummaryResult(
                fileId,
                fileName,
                durationSeconds,
                streamUrl,
                downloadUrl
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