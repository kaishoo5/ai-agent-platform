package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.service.AgentProgressReporter;
import com.agent.aiagent.domain.chat.service.ChatStreamingExecutor;
import com.agent.aiagent.domain.rag.model.ChatSource;
import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.video.model.VideoResult;
import com.agent.aiagent.domain.video.service.VideoSummaryFileService;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallingExecutor {

    private static final int MAX_TOOL_CALL_ROUNDS = 12;

    private static final String AI_EDIT_CODE_TOOL_NAME =
            "ai_edit_code";

    private static final String VIDEO_SUMMARY_GENERATE_TOOL_NAME =
            "video_summary_generate";

    private static final String VIDEO_SHORTS_GENERATE_TOOL_NAME =
            "video_shorts_generate";

    private static final String ATTACHMENT_PROJECT_STRUCTURE_TOOL_NAME =
            "attachment_project_structure";

    private static final String ATTACHMENT_CODEBASE_OVERVIEW_TOOL_NAME =
            "attachment_codebase_overview";

    private static final String ATTACHMENT_SEARCH_TOOL_NAME =
            "attachment_search";

    private static final String ATTACHMENT_READ_SOURCE_TOOL_NAME =
            "attachment_read_source";

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

        List<ChatModelTool> originalTools =
                chatModelRequest.tools();

        ChatModelRequest currentRequest =
                appendAttachmentCodeGroundingInstruction(
                        chatModelRequest
                );

        List<VideoResult> videoResults =
                new ArrayList<>();

        Set<String> executedToolCalls =
                new HashSet<>();

        /*
         * 최종 답변 단계에서 앞쪽 Tool Result가 context truncation으로
         * 밀려나는 것을 막기 위해, 성공한 attachment_read_source 결과를
         * 별도로 보존한다.
         *
         * attachment_search 결과를 Executor가 임의로 하나 골라 자동 read하지 않고,
         * 모델이 검색 결과의 SOURCE_PATH를 보고 attachment_read_source를
         * 직접 선택하도록 한다.
         */
        List<String> attachmentSourceGroundings =
                new ArrayList<>();

        int consecutiveDuplicateToolCallCount = 0;
        int sourceGroundingCompletionCheckCount = 0;
        int duplicateRecoveryCount = 0;

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

                /*
                 * 실제 attachment_read_source grounding을 하나라도 확보한 뒤
                 * 모델이 Tool Call 없이 일반 content를 반환한 경우,
                 * 탐색 중 request의 content를 그대로 사용자에게 보내지 않는다.
                 *
                 * 탐색 request에는 Conversation Summary, Agent Memory,
                 * attachment_search / structure / overview 결과 등이 남아 있을 수 있으므로
                 * 실제 SOURCE grounding만 사용하는 clean final request를 새로 만든다.
                 */
                if (!attachmentSourceGroundings.isEmpty()) {

                    /*
                     * 실제 source를 확보한 뒤 모델이 Tool Call 없이 답변하려는 경우,
                     * 곧바로 final로 종료하지 않고 한 번은 탐색 완료 여부를 다시 확인한다.
                     *
                     * 단일 파일 질문은 여기서 "충분하다"고 판단하여 다음 round에서
                     * 다시 Tool Call 없이 응답할 수 있고,
                     * 여러 파일의 호출 관계를 묻는 질문은 아직 확인하지 않은 연결 source를
                     * 추가로 search/read할 수 있다.
                     */
                    if (sourceGroundingCompletionCheckCount == 0) {

                        sourceGroundingCompletionCheckCount++;

                        log.info(
                                "Source grounding 확보 후 탐색 종료 응답 감지. "
                                        + "원래 사용자 질문에 필요한 source가 충분한지 한 번 더 확인합니다. "
                                        + "round={}, groundingCount={}, contentLength={}",
                                round,
                                attachmentSourceGroundings.size(),
                                content == null
                                        ? 0
                                        : content.length()
                        );

                        currentRequest =
                                appendSourceGroundingCompletionCheck(
                                        currentRequest,
                                        request
                                );

                        continue;
                    }

                    log.info(
                            "Source grounding 확인 완료 후 clean final request를 생성합니다. "
                                    + "round={}, groundingCount={}, contentLength={}",
                            round,
                            attachmentSourceGroundings.size(),
                            content == null
                                    ? 0
                                    : content.length()
                    );

                    ChatModelRequest finalRequest =
                            createFinalRequest(
                                    currentRequest,
                                    attachmentSourceGroundings
                            );

                    if (progressReporter != null) {
                        progressReporter.running(
                                "answer_generation",
                                "답변 생성 중..."
                        );
                    }

                    return executeFinalResponse(
                            emitter,
                            request,
                            finalRequest,
                            videoResults,
                            safeSources
                    );
                }

                /*
                 * ZIP 프로젝트 분석에서 structure / overview만 확인하고
                 * 실제 attachment_search + attachment_read_source가 없으면
                 * 응답 길이/형식과 무관하게 최종 답변을 허용하지 않는다.
                 */
                if (
                        shouldContinueAttachmentSourceGrounding(
                                executedToolCalls,
                                attachmentSourceGroundings
                        )
                ) {
                    log.warn(
                            "ZIP 프로젝트 실제 소스 grounding이 부족합니다. "
                                    + "최종 답변 생성을 보류하고 소스 탐색을 계속합니다. "
                                    + "round={}, contentLength={}, content={}",
                            round,
                            content == null
                                    ? 0
                                    : content.length(),
                            summarizeContentForLog(
                                    content
                            )
                    );

                    currentRequest =
                            appendAttachmentSourceGroundingReminder(
                                    currentRequest
                            );

                    continue;
                }


                if (
                        isIncompleteFinalContent(
                                content,
                                executedToolCalls
                        )
                ) {
                    log.warn(
                            "Tool Calling 최종 응답이 비어 있거나 불완전합니다. "
                                    + "Tool을 비활성화하고 최종 답변을 재생성합니다. "
                                    + "round={}, contentLength={}, content={}",
                            round,
                            content == null
                                    ? 0
                                    : content.length(),
                            summarizeContentForLog(
                                    content
                            )
                    );

                    ChatModelRequest finalRequest =
                            createFinalRequest(
                                    currentRequest,
                                    attachmentSourceGroundings
                            );

                    if (progressReporter != null) {
                        progressReporter.running(
                                "answer_generation",
                                "답변 생성 중..."
                        );
                    }

                    return executeFinalResponse(
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

            /*
             * 동일한 Tool + 동일한 arguments가 이미 실행된 경우
             * 같은 Tool을 다시 실행하지 않고 Tool Calling을 종료한다.
             *
             * 예:
             * web_search(
             *     query="September 25 2026 AI news",
             *     searchDepth="basic",
             *     maxResults=10
             * )
             *
             * 위 호출이 이미 실행된 상태에서 동일한 호출이 다시 나오면
             * Tavily API를 다시 호출하지 않고 기존 Tool 결과를 이용해
             * 최종 답변을 생성한다.
             */
            boolean duplicateToolCall =
                    response.toolCalls()
                            .stream()
                            .anyMatch(toolCall -> {
                                String toolCallKey =
                                        createToolCallKey(
                                                toolCall.name(),
                                                toolCall.arguments()
                                        );

                                return executedToolCalls.contains(
                                        toolCallKey
                                );
                            });

            if (duplicateToolCall) {
                consecutiveDuplicateToolCallCount++;

                log.warn(
                        "동일 Tool Call 반복 감지. "
                                + "round={}, duplicateCount={}, tools={}",
                        round,
                        consecutiveDuplicateToolCallCount,
                        response.toolCalls()
                                .stream()
                                .map(toolCall ->
                                        toolCall.name()
                                )
                                .toList()
                );

                if (
                        !attachmentSourceGroundings.isEmpty()
                                && consecutiveDuplicateToolCallCount >= 2
                ) {

                    /*
                     * 동일 Tool Call이 반복됐다는 사실만으로
                     * 사용자 질문에 필요한 source가 충분하다고 판단하지 않는다.
                     *
                     * 한 번은 현재 검색어/심볼에서 벗어나
                     * 아직 확인하지 못한 연결 지점을 다른 단서로 탐색하도록 한다.
                     */
                    if (duplicateRecoveryCount == 0) {

                        duplicateRecoveryCount++;

                        log.info(
                                "성공한 source grounding 확보 후 동일 Tool Call이 연속 반복되었습니다. "
                                        + "즉시 종료하지 않고 다른 검색어/심볼로 탐색을 한 번 복구합니다. "
                                        + "round={}, duplicateCount={}, groundingCount={}",
                                round,
                                consecutiveDuplicateToolCallCount,
                                attachmentSourceGroundings.size()
                        );

                        currentRequest =
                                appendDuplicateSearchRecoveryReminder(
                                        currentRequest,
                                        request,
                                        response.toolCalls()
                                );

                        /*
                         * 다음 round에서 새로운 Tool Call을 정상적으로 판단할 수 있도록
                         * 연속 중복 횟수만 초기화한다.
                         *
                         * executedToolCalls는 유지한다.
                         * 그래야 이미 실행한 동일 Tool Call이 다시 실제 실행되지 않는다.
                         */
                        consecutiveDuplicateToolCallCount = 0;

                        if (progressReporter != null) {
                            progressReporter.completed(
                                    "tool_execution",
                                    "중복 탐색 복구"
                            );
                        }

                        continue;
                    }

                    log.info(
                            "중복 Tool Call 탐색 복구 후에도 동일 호출이 반복되었습니다. "
                                    + "추가 탐색을 종료하고 최종 답변을 생성합니다. "
                                    + "round={}, duplicateCount={}, groundingCount={}",
                            round,
                            consecutiveDuplicateToolCallCount,
                            attachmentSourceGroundings.size()
                    );

                    ChatModelRequest finalRequest =
                            createFinalRequest(
                                    currentRequest,
                                    attachmentSourceGroundings
                            );

                    if (progressReporter != null) {
                        progressReporter.running(
                                "answer_generation",
                                "답변 생성 중..."
                        );
                    }

                    return executeFinalResponse(
                            emitter,
                            request,
                            finalRequest,
                            videoResults,
                            safeSources
                    );
                }

                currentRequest =
                        appendDuplicateToolCallReminder(
                                currentRequest,
                                response.toolCalls()
                        );

                if (progressReporter != null) {
                    progressReporter.completed(
                            "tool_execution",
                            "중복 도구 실행 생략"
                    );
                }

                continue;
            }

            consecutiveDuplicateToolCallCount = 0;

            response.toolCalls()
                    .forEach(toolCall ->
                            executedToolCalls.add(
                                    createToolCallKey(
                                            toolCall.name(),
                                            toolCall.arguments()
                                    )
                            )
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

            /*
             * 모델이 직접 선택해서 읽은 실제 SOURCE_PATH 결과를 별도로 보존한다.
             * 최종 답변 생성 시 이 목록을 request 끝부분에 다시 배치하여
             * 오래된 read_source 결과가 context 앞쪽에서 유실되는 문제를 줄인다.
             */
            for (int index = 0; index < response.toolCalls().size(); index++) {
                ChatModelToolCall toolCall =
                        response.toolCalls().get(
                                index
                        );

                ToolResult toolResult =
                        toolResults.get(
                                index
                        );

                if (
                        ATTACHMENT_READ_SOURCE_TOOL_NAME.equals(
                                toolCall.name()
                        )
                                && toolResult.success()
                                && toolResult.content() != null
                                && !toolResult.content().isBlank()
                ) {
                    String sourcePath =
                            getSourcePathArgument(
                                    toolCall
                            );

                    attachmentSourceGroundings.add(
                            formatAttachmentSourceGrounding(
                                    sourcePath,
                                    toolResult.content()
                            )
                    );
                }
            }

            /*
             * 우선 모델이 요청한 Tool Call과 결과를 conversation에 추가한다.
             */
            currentRequest =
                    chatModelRequestBuilder.appendToolResults(
                            currentRequest,
                            response.toolCalls(),
                            toolResults,
                            originalTools
                    );

            /*
             * attachment_search가 파일명/경로와 "명확하게 1:1 일치"한
             * exactSourcePath를 반환한 경우에만 Executor가 자동으로 source를 읽는다.
             *
             * 검색 점수 1등이나 selectedSourcePath를 신뢰하지 않는다.
             * 함수/심볼 검색처럼 후보가 애매한 경우에는 자동 read하지 않고
             * 기존처럼 모델이 SOURCE_PATH를 선택한다.
             */
            for (int index = 0; index < response.toolCalls().size(); index++) {
                ChatModelToolCall searchToolCall =
                        response.toolCalls().get(
                                index
                        );

                ToolResult searchToolResult =
                        toolResults.get(
                                index
                        );

                if (
                        !ATTACHMENT_SEARCH_TOOL_NAME.equals(
                                searchToolCall.name()
                        )
                                || !searchToolResult.success()
                ) {
                    continue;
                }

                String exactSourcePath =
                        getExactSourcePath(
                                searchToolResult
                        );

                if (
                        exactSourcePath == null
                                || exactSourcePath.isBlank()
                ) {
                    continue;
                }

                ChatModelToolCall readSourceToolCall =
                        new ChatModelToolCall(
                                ATTACHMENT_READ_SOURCE_TOOL_NAME,
                                Map.of(
                                        "sourcePath",
                                        exactSourcePath
                                )
                        );

                String readSourceToolCallKey =
                        createToolCallKey(
                                readSourceToolCall.name(),
                                readSourceToolCall.arguments()
                        );

                if (
                        executedToolCalls.contains(
                                readSourceToolCallKey
                        )
                ) {
                    continue;
                }

                log.info(
                        "attachment_search exactSourcePath 자동 read. "
                                + "query={}, sourcePath={}",
                        getStringArgument(
                                searchToolCall.arguments(),
                                "query"
                        ),
                        exactSourcePath
                );

                List<ToolResult> readSourceResults =
                        toolCallProcessor.execute(
                                List.of(
                                        readSourceToolCall
                                ),
                                toolExecutionContext
                        );

                if (
                        readSourceResults == null
                                || readSourceResults.isEmpty()
                ) {
                    continue;
                }

                ToolResult readSourceResult =
                        readSourceResults.get(0);

                executedToolCalls.add(
                        readSourceToolCallKey
                );

                currentRequest =
                        chatModelRequestBuilder.appendToolResults(
                                currentRequest,
                                List.of(
                                        readSourceToolCall
                                ),
                                List.of(
                                        readSourceResult
                                ),
                                originalTools
                        );

                if (
                        readSourceResult.success()
                                && readSourceResult.content() != null
                                && !readSourceResult.content().isBlank()
                ) {
                    attachmentSourceGroundings.add(
                            formatAttachmentSourceGrounding(
                                    exactSourcePath,
                                    readSourceResult.content()
                            )
                    );
                }
            }

            /*
             * attachment_search 결과의 SOURCE_PATH 선택은 모델에게 맡긴다.
             *
             * 이전 구현처럼 Executor가 selectedSourcePath 하나를 골라
             * attachment_read_source를 강제 실행하면, 검색 후보 중 잘못된 파일
             * 하나가 선택되는 순간 이후 조사 전체가 그 파일에 끌려갈 수 있다.
             *
             * 이제 attachment_search는 후보와 snippet을 제공하고,
             * 모델이 그 결과를 보고 필요한 SOURCE_PATH를
             * attachment_read_source로 직접 읽는다.
             */
            /*
             * ZIP 프로젝트 전체 분석에서는
             *
             * attachment_project_structure
             *     ↓
             * attachment_codebase_overview
             *
             * 순서를 모델 선택에 맡기지 않는다.
             *
             * structure가 성공했다면 overview를 즉시 실행하고,
             * 해당 Tool Call / Tool Result까지 conversation에 추가한다.
             *
             * 이렇게 해야 다음 round에서 모델이
             * overview 호출을 생략하고 structure 정보만으로
             * 최종 답변을 생성하는 문제를 막을 수 있다.
             */
            boolean attachmentProjectStructureExecutedSuccessfully =
                    wasToolExecutedSuccessfully(
                            response.toolCalls(),
                            toolResults,
                            ATTACHMENT_PROJECT_STRUCTURE_TOOL_NAME
                    );

            if (attachmentProjectStructureExecutedSuccessfully) {
                boolean attachmentCodebaseOverviewAlreadyExecuted =
                        executedToolCalls.stream()
                                .anyMatch(toolCallKey ->
                                        toolCallKey.startsWith(
                                                ATTACHMENT_CODEBASE_OVERVIEW_TOOL_NAME
                                                        + ":"
                                        )
                                );

                if (!attachmentCodebaseOverviewAlreadyExecuted) {
                    ChatModelTool overviewTool =
                            findTool(
                                    originalTools,
                                    ATTACHMENT_CODEBASE_OVERVIEW_TOOL_NAME
                            );

                    if (overviewTool == null) {
                        log.warn(
                                "Attachment Project Structure Tool 실행 후 "
                                        + "Attachment Codebase Overview Tool을 찾지 못했습니다."
                        );
                    } else {
                        if (progressReporter != null) {
                            progressReporter.running(
                                    "tool_execution",
                                    "프로젝트 주요 코드 분석 중..."
                            );
                        }

                        /*
                         * 모델에게 다시 Tool 선택을 시키지 않고
                         * overview Tool Call을 직접 생성한다.
                         *
                         * attachment_codebase_overview는 arguments가 필요 없다.
                         */
                        ChatModelToolCall overviewToolCall =
                                new ChatModelToolCall(
                                        ATTACHMENT_CODEBASE_OVERVIEW_TOOL_NAME,
                                        Map.of()
                                );

                        String overviewToolCallKey =
                                createToolCallKey(
                                        overviewToolCall.name(),
                                        overviewToolCall.arguments()
                                );

                        executedToolCalls.add(
                                overviewToolCallKey
                        );

                        log.info(
                                "Attachment Project Structure Tool 실행 후 "
                                        + "Attachment Codebase Overview Tool을 "
                                        + "자동 실행합니다. round={}",
                                round
                        );

                        List<ChatModelToolCall> overviewToolCalls =
                                List.of(
                                        overviewToolCall
                                );

                        List<ToolResult> overviewToolResults =
                                toolCallProcessor.execute(
                                        overviewToolCalls,
                                        toolExecutionContext
                                );

                        ToolResult overviewToolResult =
                                overviewToolResults.getFirst();

                        if (progressReporter != null) {
                            progressReporter.completed(
                                    "tool_execution",
                                    overviewToolResult.success()
                                            ? "프로젝트 주요 코드 분석 완료"
                                            : "프로젝트 주요 코드 분석 실패"
                            );
                        }

                        currentRequest =
                                chatModelRequestBuilder.appendToolResults(
                                        currentRequest,
                                        overviewToolCalls,
                                        overviewToolResults,
                                        originalTools
                                );

                        if (overviewToolResult.success()) {
                            log.info(
                                    "Attachment Codebase Overview Tool 자동 실행 완료. "
                                            + "round={}",
                                    round
                            );
                        } else {
                            log.warn(
                                    "Attachment Codebase Overview Tool 자동 실행 실패. "
                                            + "round={}, result={}",
                                    round,
                                    overviewToolResult
                            );
                        }
                    }
                }
            }

            /*
             * Tool 실행이 끝난 뒤 다음 round로 넘어가기 전에
             * 현재 사용자의 원래 질문을 다시 명시한다.
             *
             * Tool Result / grounding system message가 계속 누적되면
             * 모델이 원래 사용자 질문을 놓칠 수 있으므로,
             * 매 round 마지막에 한 번만 reminder를 추가한다.
             */
            currentRequest =
                    appendOriginalQuestionReminder(
                            currentRequest,
                            request
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
                        createFinalRequest(
                                currentRequest,
                                attachmentSourceGroundings
                        );

                if (progressReporter != null) {
                    progressReporter.running(
                            "answer_generation",
                            "답변 생성 중..."
                    );
                }

                return executeFinalResponse(
                        emitter,
                        request,
                        finalRequest,
                        videoResults,
                        safeSources
                );
            }
        }

        /*
         * 최대 Tool Calling 횟수에 도달한 경우
         * 더 이상 Tool을 제공하지 않는다.
         *
         * currentRequest에는 지금까지 실행한 모든 Tool 결과가
         * 포함되어 있으므로 tools만 제거한 뒤 최종 답변을 생성한다.
         *
         * tools를 그대로 넘기면 모델이 다시 Tool Call을 시도할 수 있고,
         * 최종 답변 대신 비정상적인 응답이 생성될 수 있다.
         */
        log.warn(
                "Tool Calling 최대 반복 횟수에 도달했습니다. "
                        + "Tool을 비활성화하고 최종 답변을 생성합니다. maxRounds={}",
                MAX_TOOL_CALL_ROUNDS
        );

        ChatModelRequest finalRequest =
                createFinalRequest(
                        currentRequest,
                        attachmentSourceGroundings
                );

        if (progressReporter != null) {
            progressReporter.running(
                    "answer_generation",
                    "답변 생성 중..."
            );
        }

        return executeFinalResponse(
                emitter,
                request,
                finalRequest,
                videoResults,
                safeSources
        );
    }

    private ChatModelRequest appendDuplicateSearchRecoveryReminder(
            ChatModelRequest currentRequest,
            ChatRequest request,
            List<ChatModelToolCall> duplicateToolCalls
    ) {
        List<ChatModelMessage> messages =
                new ArrayList<>(
                        currentRequest.messages()
                );

        String originalQuestion =
                request == null
                        || request.getMessages() == null
                        || request.getMessages().isEmpty()
                        ? null
                        : request.getMessages()
                        .stream()
                        .filter(message ->
                                message != null
                                        && "user".equalsIgnoreCase(
                                        message.getRole()
                                )
                        )
                        .reduce((first, second) -> second)
                        .map(message ->
                                message.getContent()
                        )
                        .map(String::trim)
                        .filter(content ->
                                !content.isBlank()
                        )
                        .orElse(null);

        String duplicateCalls =
                duplicateToolCalls == null
                        ? ""
                        : duplicateToolCalls.stream()
                        .map(toolCall ->
                                toolCall.name()
                                        + " "
                                        + String.valueOf(
                                        toolCall.arguments()
                                )
                        )
                        .collect(
                                java.util.stream.Collectors.joining(
                                        "\n"
                                )
                        );

        messages.add(
                new ChatModelMessage(
                        "system",
                        """
                        [중복 SOURCE 탐색 복구]
    
                        원래 사용자 질문:
    
                        %s
    
                        방금 아래 Tool Call을 동일한 arguments로 반복했습니다.
    
                        %s
    
                        이 호출은 이미 실행되었으므로
                        같은 query 또는 같은 arguments를 다시 사용하지 마세요.
    
                        아직 최종 답변을 작성하지 마세요.
    
                        원래 사용자 질문에서 요구한 전체 흐름과
                        지금까지 실제 attachment_read_source로 읽은 source를 비교하세요.
    
                        아직 실제 source로 확인하지 못한 단계가 있다면,
                        방금 실패한 검색어를 반복하지 말고
                        이미 읽은 source에 실제 등장한 다른 단서를 사용하세요.
    
                        사용할 수 있는 단서는 예를 들면 다음과 같습니다.
    
                        - 실제 호출되는 클래스명
                        - 실제 호출되는 메서드명
                        - import된 타입
                        - 주입된 service 또는 component
                        - 실제 endpoint 문자열
                        - 실제 인터페이스 또는 구현체 이름
    
                        위 단서 중 실제 source에 존재하는 것을 이용해
                        attachment_search를 새 query로 실행하세요.
    
                        검색 결과에서 정확한 SOURCE_PATH를 확인한 뒤
                        필요한 구현은 attachment_read_source로 읽으세요.
    
                        같은 검색어를 조금만 변형해서 반복하는 것보다
                        아직 확인하지 못한 연결 단계의 실제 symbol을 우선하세요.
    
                        파일명, 클래스명, 메서드명, endpoint를 추측해서 만들지 마세요.
                        UUID/fileId를 query나 SOURCE_PATH로 사용하지 마세요.
    
                        원래 사용자 질문의 모든 필요한 연결 단계가
                        이미 실제 source로 확인됐다면
                        Tool Call 없이 자연어 응답을 생성하세요.
                        """.formatted(
                                originalQuestion == null
                                        ? "현재 사용자 질문"
                                        : originalQuestion,
                                duplicateCalls
                        )
                )
        );

        return new ChatModelRequest(
                currentRequest.modelType(),
                List.copyOf(
                        messages
                ),
                currentRequest.tools()
        );
    }

    /**
     * Tool 단계가 끝난 뒤의 최종 답변은 non-streaming chatOnce로 확정한다.
     *
     * Tool Calling 중간 응답과 달리 이 시점에는 tools가 비어 있으므로
     * 모델이 다시 Tool Call JSON을 생성할 이유가 없다.
     *
     * 첫 응답이 비어 있거나 명백히 불완전하면 동일한 최종 요청을 한 번 더
     * 시도한다. 정상 응답을 얻으면 executeCompletedResponse()를 통해
     * 기존 SSE message/done 전송 및 저장 흐름을 그대로 사용한다.
     *
     * 두 번 모두 실패한 경우에만 기존 streaming 경로를 마지막 fallback으로
     * 사용한다.
     */
    private SseEmitter executeFinalResponse(
            SseEmitter emitter,
            ChatRequest request,
            ChatModelRequest finalRequest,
            List<VideoResult> videoResults,
            List<ChatSource> safeSources
    ) {
        final int maxAttempts = 3;

        ChatModelRequest attemptRequest =
                finalRequest;

        String bestNonBlankContent =
                null;

        for (
                int attempt = 1;
                attempt <= maxAttempts;
                attempt++
        ) {
            ChatModelResponse finalResponse =
                    chatModelProvider.chatOnce(
                            attemptRequest
                    );

            String content =
                    finalResponse.content();

            if (
                    content != null
                            && !content.isBlank()
                            && (
                            bestNonBlankContent == null
                                    || content.length() > bestNonBlankContent.length()
                    )
            ) {
                bestNonBlankContent =
                        content;
            }

            if (
                    !isIncompleteFinalContent(
                            content,
                            Set.of("__final_response__")
                    )
            ) {
                log.info(
                        "Tool Calling 최종 답변 생성 완료. attempt={}, contentLength={}",
                        attempt,
                        content.length()
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

            log.warn(
                    "Tool Calling 최종 답변이 비어 있거나 불완전합니다. "
                            + "최종 답변 생성을 재시도합니다. attempt={}/{}, "
                            + "contentLength={}, content={}",
                    attempt,
                    maxAttempts,
                    content == null
                            ? 0
                            : content.length(),
                    summarizeContentForLog(
                            content
                    )
            );

            attemptRequest =
                    createFinalRetryRequest(
                            finalRequest,
                            content
                    );
        }

        /*
         * 완결성 검사를 전부 통과하지 못했더라도
         * 실제 생성된 답변이 하나라도 있으면 버리지 않는다.
         */
        if (
                bestNonBlankContent != null
                        && !bestNonBlankContent.isBlank()
        ) {
            log.warn(
                    "Tool Calling 최종 답변이 완결성 검사를 통과하지 못했지만 "
                            + "생성된 최선의 non-blank 응답을 사용합니다. contentLength={}",
                    bestNonBlankContent.length()
            );

            return chatStreamingExecutor
                    .executeCompletedResponse(
                            emitter,
                            request,
                            bestNonBlankContent,
                            videoResults,
                            safeSources
                    );
        }

        /*
         * 세 번 모두 완전히 빈 응답일 때만
         * 기존 streaming 경로를 마지막 fallback으로 사용한다.
         */
        log.warn(
                "Tool Calling 최종 답변 non-streaming 생성이 모두 빈 응답으로 실패했습니다. "
                        + "기존 streaming 경로로 마지막 재시도합니다."
        );

        return chatStreamingExecutor.execute(
                emitter,
                request,
                finalRequest,
                videoResults,
                safeSources
        );
    }

    private ChatModelRequest createFinalRetryRequest(
            ChatModelRequest finalRequest,
            String previousContent
    ) {
        List<ChatModelMessage> messages =
                new ArrayList<>(
                        finalRequest.messages()
                );

        boolean previousResponseWasBlank =
                previousContent == null
                        || previousContent.isBlank();

        String retryInstruction;

        if (previousResponseWasBlank) {
            retryInstruction =
                    """
                    직전 최종 답변 생성이 빈 응답으로 끝났습니다.
    
                    도구 호출이나 JSON을 출력하지 말고,
                    사용자의 현재 질문에 대한 최종 자연어 답변을 반드시 작성하세요.
    
                    이미 확보된 근거만 사용하고,
                    확인되지 않은 내용은 추측하지 마세요.
                    """;
        } else {
            retryInstruction =
                    """
                    직전 최종 답변이 출력 도중 끊겨 완결되지 않았습니다.
    
                    직전 답변을 이어 쓰지 말고 처음부터 다시 작성하세요.
                    이번에는 답변이 중간에서 끊기지 않도록 간결하게 완결하세요.
    
                    Markdown 표나 긴 코드 블록 때문에 답변이 끊길 수 있으므로,
                    꼭 필요한 경우가 아니면 일반 문단과 짧은 목록을 사용하세요.
    
                    도구 호출이나 JSON을 출력하지 말고,
                    이미 확보된 근거만 사용하여 사용자의 현재 질문에 직접 답하세요.
                    확인되지 않은 내용은 추측하지 마세요.
                    """;
        }

        messages.add(
                new ChatModelMessage(
                        "system",
                        retryInstruction
                )
        );

        return new ChatModelRequest(
                finalRequest.modelType(),
                List.copyOf(
                        messages
                ),
                List.of()
        );
    }

    /**
     * Tool 실행 뒤 모델이 실제 답변을 만들지 못하고
     * 제목/Markdown 시작 부분만 반환한 경우를 최종 답변으로 확정하지 않는다.
     *
     * 특정 언어, 프레임워크, 파일 확장자에는 의존하지 않는다.
     */
    private boolean isIncompleteFinalContent(
            String content,
            Set<String> executedToolCalls
    ) {
        if (content == null || content.isBlank()) {
            return true;
        }

        if (
                executedToolCalls == null
                        || executedToolCalls.isEmpty()
        ) {
            return false;
        }

        String normalized =
                content
                        .replace("\r\n", "\n")
                        .trim();

        // Tool을 실제로 사용한 뒤 수십 자만 생성하고 끝난 응답은
        // 정상적인 근거 기반 최종 답변으로 보기 어렵다.
        if (normalized.length() < 80) {
            return true;
        }

        // Markdown 표를 시작만 하고 끊긴 대표적인 형태를 잡는다.
        String[] lines =
                normalized.split("\n");

        String lastLine =
                lines[lines.length - 1].trim();

        return "|".equals(lastLine)
                || "```".equals(lastLine);
    }

    /**
     * ZIP 프로젝트 구조를 확인한 뒤에도 실제 소스 검색/읽기가 전혀 없으면
     * 불완전 응답을 최종 답변으로 재생성하지 않고 조사를 계속한다.
     *
     * 특정 언어, 프레임워크, frontend/backend, 확장자에는 의존하지 않는다.
     */
    private boolean shouldContinueAttachmentSourceGrounding(
            Set<String> executedToolCalls,
            List<String> attachmentSourceGroundings
    ) {
        if (
                executedToolCalls == null
                        || executedToolCalls.isEmpty()
        ) {
            return false;
        }

        boolean projectStructureExecuted =
                wasToolCallExecuted(
                        executedToolCalls,
                        ATTACHMENT_PROJECT_STRUCTURE_TOOL_NAME
                );

        boolean codebaseOverviewExecuted =
                wasToolCallExecuted(
                        executedToolCalls,
                        ATTACHMENT_CODEBASE_OVERVIEW_TOOL_NAME
                );

        boolean attachmentSearchExecuted =
                wasToolCallExecuted(
                        executedToolCalls,
                        ATTACHMENT_SEARCH_TOOL_NAME
                );

        boolean attachmentProjectAnalysisStarted =
                projectStructureExecuted
                        || codebaseOverviewExecuted
                        || attachmentSearchExecuted;

        if (!attachmentProjectAnalysisStarted) {
            return false;
        }

        /*
         * 프로젝트 분석이 시작됐다면 Tool Call을 단순히 실행했다는 사실이 아니라
         * attachment_read_source가 실제로 성공해서 source grounding을 확보했는지를
         * 최종 답변 허용 기준으로 사용한다.
         *
         * structure / overview에서 정확한 SOURCE_PATH를 찾아 모델이 곧바로
         * attachment_read_source를 성공시킨 경우 attachment_search를 강제하지 않는다.
         *
         * 반대로 attachment_read_source 호출 자체는 있었더라도 실패했다면
         * attachmentSourceGroundings가 비어 있으므로 조사를 계속한다.
         */
        return attachmentSourceGroundings == null
                || attachmentSourceGroundings.isEmpty();
    }

    private boolean wasToolCallExecuted(
            Set<String> executedToolCalls,
            String toolName
    ) {
        String prefix =
                toolName + ":";

        return executedToolCalls.stream()
                .anyMatch(toolCallKey ->
                        toolCallKey != null
                                && toolCallKey.startsWith(
                                prefix
                        )
                );
    }

    /**
     * overview만 본 상태에서 불완전 응답이 나온 경우
     * 다음 round에서 실제 SOURCE_PATH를 찾아 읽도록 모델에 명시한다.
     */
    private ChatModelRequest appendAttachmentSourceGroundingReminder(
            ChatModelRequest currentRequest
    ) {
        List<ChatModelMessage> messages =
                new ArrayList<>(
                        currentRequest.messages()
                );

        messages.add(
                new ChatModelMessage(
                        "system",
                        """
                        방금 생성한 답변은 불완전하며,
                        아직 실제 프로젝트 소스 확인이 충분하지 않습니다.

                        지금 최종 답변을 작성하지 마세요.

                        프로젝트 구조/overview만으로 구현을 추측하지 말고,
                        사용자의 현재 질문과 직접 관련된 실제 코드 위치를
                        attachment_search로 찾으세요.

                        검색 결과에서 확인한 SOURCE_PATH의 실제 구현은
                        attachment_read_source로 읽으세요.

                        읽은 코드에서 다른 파일, 클래스, 함수, API, 설정 또는
                        심볼의 구현을 확인해야 정확한 답변이 가능하다면
                        그 연결 지점을 계속 추적하세요.

                        특정 언어, 프레임워크, 파일 확장자,
                        frontend/backend 구조를 미리 가정하지 마세요.

                        실제 Tool Result에서 확인하지 않은 구현을
                        만들어내지 마세요.
                        """
                )
        );

        return new ChatModelRequest(
                currentRequest.modelType(),
                List.copyOf(
                        messages
                ),
                currentRequest.tools()
        );
    }

    private String summarizeContentForLog(
            String content
    ) {
        if (content == null) {
            return "<null>";
        }

        String normalized =
                content
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .trim();

        int maxLength = 160;

        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 첨부 ZIP 프로젝트의 구체적인 코드 질문에서 overview만 보고
     * 구현을 추측하지 않도록 Tool Calling 원칙을 추가한다.
     */
    private ChatModelRequest appendAttachmentCodeGroundingInstruction(
            ChatModelRequest currentRequest
    ) {
        if (
                currentRequest == null
                        || currentRequest.tools() == null
                        || currentRequest.tools().isEmpty()
        ) {
            return currentRequest;
        }

        boolean attachmentReadSourceAvailable =
                currentRequest.tools()
                        .stream()
                        .anyMatch(tool ->
                                "attachment_read_source".equals(
                                        tool.name()
                                )
                        );

        if (!attachmentReadSourceAvailable) {
            return currentRequest;
        }

        List<ChatModelMessage> messages =
                new ArrayList<>(
                        currentRequest.messages()
                );

        messages.add(
                new ChatModelMessage(
                        "system",
                        """
                        [첨부 프로젝트 코드 분석 원칙]

                        첨부 ZIP 프로젝트에 대한 질문을 처리할 때 다음 원칙을 따르세요.

                        1. 사용자가 프로젝트 구조, 기술 스택, 주요 디렉터리처럼
                           개괄적인 정보만 요청했다면
                           attachment_project_structure와
                           attachment_codebase_overview 결과만으로 충분할 수 있습니다.

                        2. 사용자가 특정 코드의 동작, 원인, 수정 방법, 구현 방법,
                           함수/컴포넌트/API/상태/라우팅/데이터 흐름을 질문했다면
                           overview 정보만으로 최종 답변하지 마세요.

                        3. 구체적인 코드 질문에서는 실제 구현 근거가 필요합니다.
                           필요한 SOURCE_PATH를 모르면 attachment_search로 찾고,
                           SOURCE_PATH를 알게 되면 attachment_read_source로
                           실제 소스 내용을 읽은 뒤 답변하세요.

                        4. attachment_search 결과의 snippet만으로
                           함수 전체 동작이나 호출 관계를 단정하지 마세요.
                           구현 판단이 필요하면 attachment_read_source를 사용하세요.

                        5. 읽은 소스에서 다른 프로젝트 파일의 구현을 확인해야
                           정확히 답할 수 있다면 해당 파일도 찾아서 읽으세요.
                           이미 충분한 근거가 확보됐다면 불필요한 Tool Call을
                           반복하지 마세요.

                        6. 사용자가 "어떻게 수정해?", "코드로 만들어줘",
                           "어디를 고쳐야 해?"처럼 구현 변경을 요청했다면
                           현재 프로젝트의 실제 관련 소스를 먼저 확인하고,
                           그 코드와 구조에 맞는 변경안을 제시하세요.

                        7. Tool Result에서 확인하지 않은 파일명, 함수명, API,
                           import, 상태 구조 또는 호출 관계를 만들어내지 마세요.

                        목표는 일반적인 개발 지식만 설명하는 것이 아니라
                        실제 첨부 프로젝트 소스를 근거로 개발 질문에 답하는 것입니다.
                        """
                )
        );

        return new ChatModelRequest(
                currentRequest.modelType(),
                List.copyOf(
                        messages
                ),
                currentRequest.tools()
        );
    }

    /**
     * Tool Calling을 종료하고 최종 답변만 생성하기 위한 요청을 만든다.
     *
     * 기존 messages에는 지금까지 실행된 Tool Call과 Tool Result가
     * 모두 포함되어 있으므로 그대로 유지한다.
     *
     * 마지막 system message를 추가하여 더 이상 Tool Call을 시도하지 않고
     * 지금까지 확보한 결과만으로 반드시 최종 답변을 생성하도록 한다.
     *
     * 최종 요청에서는 tools를 제거한다.
     */
    private String getSourcePathArgument(
            ChatModelToolCall toolCall
    ) {
        if (
                toolCall == null
                        || toolCall.arguments() == null
        ) {
            return null;
        }

        Object value =
                toolCall.arguments()
                        .get(
                                "sourcePath"
                        );

        if (value == null) {
            value =
                    toolCall.arguments()
                            .get(
                                    "source_path"
                            );
        }

        if (value == null) {
            return null;
        }

        String sourcePath =
                value.toString()
                        .trim();

        return sourcePath.isBlank()
                ? null
                : sourcePath;
    }

    private String formatAttachmentSourceGrounding(
            String sourcePath,
            String content
    ) {
        return """
                [READ_SOURCE]
                SOURCE_PATH: %s

                %s
                """.formatted(
                sourcePath == null
                        ? "unknown"
                        : sourcePath,
                content
        );
    }

    /**
     * 최종 답변용 request의 끝부분에 실제로 읽은 source 결과를 다시 배치한다.
     *
     * 모델의 context window가 길어졌을 때 앞쪽 Tool Result가 잘려 나가더라도
     * 마지막에 실제 source grounding이 남도록 한다.
     *
     * 같은 SOURCE_PATH를 여러 번 읽었을 경우 마지막 결과 하나만 유지하고,
     * 전체 재배치 크기는 제한한다.
     */
    private void appendFinalAttachmentSourceGroundings(
            List<ChatModelMessage> messages,
            List<String> attachmentSourceGroundings
    ) {
        if (
                messages == null
                        || attachmentSourceGroundings == null
                        || attachmentSourceGroundings.isEmpty()
        ) {
            return;
        }

        final int maxGroundingLength =
                48_000;

        LinkedHashMap<String, String> uniqueGroundings =
                new LinkedHashMap<>();

        for (String grounding : attachmentSourceGroundings) {
            if (
                    grounding == null
                            || grounding.isBlank()
            ) {
                continue;
            }

            String sourcePath =
                    extractGroundingSourcePath(
                            grounding
                    );

            uniqueGroundings.put(
                    sourcePath,
                    grounding
            );
        }

        List<String> selected =
                new ArrayList<>();

        int totalLength =
                0;

        List<String> values =
                new ArrayList<>(
                        uniqueGroundings.values()
                );

        /*
         * 최근에 읽은 source부터 확보한다.
         * 마지막 단계에서 발견한 연결 파일이 최종 답변에 중요할 가능성이 높다.
         */
        for (
                int index = values.size() - 1;
                index >= 0;
                index--
        ) {
            String grounding =
                    values.get(
                            index
                    );

            if (
                    totalLength > 0
                            && totalLength + grounding.length() > maxGroundingLength
            ) {
                continue;
            }

            selected.add(
                    grounding
            );

            totalLength +=
                    grounding.length();

            if (totalLength >= maxGroundingLength) {
                break;
            }
        }

        Collections.reverse(
                selected
        );

        if (selected.isEmpty()) {
            return;
        }

        messages.add(
                new ChatModelMessage(
                        "system",
                        """
                        [최종 답변용 실제 SOURCE grounding]

                        아래 내용은 이번 조사에서 attachment_read_source로
                        실제 읽기에 성공한 소스입니다.

                        마지막에 읽은 파일 하나만 본 것으로 착각하지 말고,
                        아래에 포함된 모든 SOURCE_PATH를 함께 근거로 사용하세요.

                        검색 snippet이나 overview보다 실제 source 내용을 우선하세요.

                        %s
                        """.formatted(
                                String.join(
                                        "\n\n",
                                        selected
                                )
                        )
                )
        );
    }

    private String extractGroundingSourcePath(
            String grounding
    ) {
        String marker =
                "SOURCE_PATH:";

        int markerIndex =
                grounding.indexOf(
                        marker
                );

        if (markerIndex < 0) {
            return grounding;
        }

        int valueStart =
                markerIndex + marker.length();

        int lineEnd =
                grounding.indexOf(
                        '\n',
                        valueStart
                );

        if (lineEnd < 0) {
            lineEnd =
                    grounding.length();
        }

        String sourcePath =
                grounding.substring(
                                valueStart,
                                lineEnd
                        )
                        .trim();

        return sourcePath.isBlank()
                ? grounding
                : sourcePath;
    }

    private ChatModelRequest createFinalRequest(
            ChatModelRequest currentRequest,
            List<String> attachmentSourceGroundings
    ) {
        /*
         * 실제 attachment_read_source grounding이 존재하는 프로젝트 분석의
         * 최종 답변은 탐색 request와 완전히 분리한다.
         *
         * 기존 request에는 Conversation Summary, Agent Memory,
         * attachment_search / structure / overview 결과와 이전 tool protocol이
         * 포함될 수 있으므로 최종 factual answer에 재사용하지 않는다.
         */
        if (
                attachmentSourceGroundings != null
                        && !attachmentSourceGroundings.isEmpty()
        ) {
            List<ChatModelMessage> messages =
                    new ArrayList<>();

            String originalUserQuestion =
                    findOriginalUserQuestion(
                            currentRequest.messages()
                    );

            messages.add(
                    new ChatModelMessage(
                            "system",
                            """
                            [최종 답변 생성 규칙]
                            
                            이제 사용자에게 최종 답변을 작성하세요.
                            
                            아래에 제공되는 [SOURCE GROUNDING]은
                            attachment_read_source를 통해 실제로 읽은 source입니다.
                            
                            최종 답변의 프로젝트 관련 사실은
                            오직 [SOURCE GROUNDING]에서 직접 확인되는 내용만 사용하세요.
                            
                            Agent Memory, Conversation Summary,
                            attachment_search 결과,
                            attachment_project_structure 결과,
                            attachment_codebase_overview 결과,
                            이전 모델 응답이나 일반적인 프레임워크 지식은
                            프로젝트 구현 사실의 근거로 사용하지 마세요.
                            
                            특히 다음 규칙을 반드시 지키세요.
                            
                            1. 실제로 읽지 않은 파일의 구현을 설명하지 마세요.
                            
                            SOURCE_PATH가 [SOURCE GROUNDING]에 존재하지 않는 파일은
                            그 파일의 내부 구현을 확인한 것으로 간주하면 안 됩니다.
                            
                            파일명이나 클래스명이 검색 결과 또는 다른 source에 등장했다는 이유만으로
                            그 파일의 구현 내용을 추측하지 마세요.
                            
                            2. 호출 관계는 실제 source에서 직접 확인되는 범위까지만 설명하세요.
                            
                            예를 들어 실제 source에
                            
                                someService.execute(...)
                            
                            가 존재한다면
                            
                                "someService.execute(...)를 호출한다"
                            
                            까지는 설명할 수 있습니다.
                            
                            하지만 SomeService의 실제 구현 source가
                            [SOURCE GROUNDING]에 없다면
                            
                                "SomeService 내부에서 무엇을 수행한다"
                            
                            라고 설명하면 안 됩니다.
                            
                            그 경우 다음과 같이 명확하게 표현하세요.
                            
                                "SomeService의 내부 구현은 현재 읽은 source에서는 확인되지 않았다."
                            
                            3. 프론트엔드와 백엔드를 임의로 연결하지 마세요.
                            
                            백엔드 Controller에 endpoint가 존재한다는 사실만으로
                            프론트엔드가 해당 endpoint를 실제 호출한다고 단정하지 마세요.
                            
                            프론트엔드 source에서 실제 API 호출을 확인한 경우에만
                            
                                "프론트엔드에서 이 API를 호출한다"
                            
                            라고 설명할 수 있습니다.
                            
                            반대로 프론트엔드 source만 읽은 경우에도
                            백엔드 내부 처리 과정을 추측하지 마세요.
                            
                            4. 사용자가 여러 단계의 전체 흐름을 요청했더라도
                            확인되지 않은 단계를 자연스럽게 채워 넣지 마세요.
                            
                            사용자가 요청한 흐름 중 일부 단계의 source만 확보됐다면
                            
                                - 실제 source로 확인된 단계
                                - 현재 읽은 source에서는 확인되지 않은 단계
                            
                            를 명확히 구분해서 답변하세요.
                            
                            전체 흐름을 완성하기 위해
                            확인되지 않은 연결 관계를 만들어내면 안 됩니다.
                            
                            5. endpoint는 실제 annotation 또는 실제 호출 코드가 있을 때만 확정하세요.
                            
                            예를 들어
                            
                                @PostMapping("/api/files")
                            
                            가 실제 source에 존재하면
                            
                                POST /api/files
                            
                            라고 설명할 수 있습니다.
                            
                            하지만 HTTP method 또는 path 중 하나라도
                            실제 source에서 확인되지 않았다면 추측하지 마세요.
                            
                            6. 클래스명, 메서드명, 함수명, 변수명, endpoint,
                            상수값, 제한값, 상태값을 추측해서 만들지 마세요.
                            
                            실제 [SOURCE GROUNDING]에 존재하는 이름과 값만 사용하세요.
                            
                            특히 숫자 상수나 제한값은
                            실제 source에 해당 선언 또는 사용 코드가 없으면 언급하지 마세요.
                            
                            7. source가 truncated=true인 경우 특히 주의하세요.
                            
                            truncated된 source는 제공된 부분까지만 읽은 것입니다.
                            
                            제공되지 않은 뒷부분에 특정 메서드, 상수 또는 로직이
                            있을 것이라고 추측하지 마세요.
                            
                            현재 제공된 부분에서 직접 확인되지 않으면
                            
                                "현재 읽은 source 범위에서는 확인되지 않았다."
                            
                            라고 답하세요.
                            
                            8. 일반적인 기술 동작을 프로젝트의 실제 구현처럼 말하지 마세요.
                            
                            Spring, React, Vue, SSE, JPA 등의 일반적인 동작을 알고 있더라도
                            그 지식을 현재 프로젝트가 실제로 그렇게 구현되어 있다는
                            근거로 사용하면 안 됩니다.
                            
                            9. 다음과 같은 추측 표현으로 사실을 우회해서 만들지 마세요.
                            
                                아마
                                추정
                                예상
                                가능성이 있다
                                일반적으로
                                보통
                                것으로 보인다
                            
                            확인되지 않은 것은 추측하지 말고
                            
                                "현재 읽은 source에서는 확인되지 않았다."
                            
                            라고 명시하세요.
                            
                            10. 답변 마지막에
                            "모두 실제 코드에서 확인했다",
                            "전체 흐름이 실제 코드로 확인됐다"
                            같은 포괄적인 표현을 사용하지 마세요.
                            
                            사용자가 요청한 모든 단계에 대한 source가
                            실제로 [SOURCE GROUNDING]에 존재하는 경우에만
                            전체 흐름이 확인됐다고 말할 수 있습니다.
                            
                            일부 단계만 확인됐다면
                            어디까지 확인됐는지를 정확하게 설명하세요.
                            
                            11. 가장 중요한 규칙:
                            
                            SOURCE GROUNDING에 없는 구현 사실을
                            답변의 자연스러운 연결을 위해 보충하지 마세요.
                            
                            답변이 조금 불완전해지는 것이
                            확인되지 않은 내용을 만들어내는 것보다 낫습니다.
                            
                            사용자의 원래 질문에 직접 답하되,
                            실제 source로 확인된 범위와
                            확인되지 않은 범위를 명확하게 구분해서 작성하세요.
                            """
                    )
            );

            messages.add(
                    new ChatModelMessage(
                            "user",
                            originalUserQuestion
                    )
            );

            appendFinalAttachmentSourceGroundings(
                    messages,
                    attachmentSourceGroundings
            );

            messages.add(
                    new ChatModelMessage(
                            "system",
                            """
                            위 사용자 질문에 답변하세요.

                            반드시 [최종 답변용 실제 SOURCE grounding]에
                            포함된 실제 코드만 프로젝트 구현 사실의 근거로 사용하세요.

                            SOURCE grounding에 없는 구현은 설명하지 마세요.

                            근거가 부족한 부분이 있다면 답변을 비우지 말고,
                            실제 source에서 확인된 범위와 확인하지 못한 범위를
                            명확하게 구분해서 작성하세요.

                            최종 자연어 답변만 출력하세요.
                            """
                    )
            );

            return new ChatModelRequest(
                    currentRequest.modelType(),
                    List.copyOf(
                            messages
                    ),
                    List.of()
            );
        }

        /*
         * 실제 source grounding이 없는 일반 Tool Calling은 기존 방식대로
         * Tool protocol만 제거하고 확보된 Tool Result를 유지한다.
         */
        List<ChatModelMessage> messages =
                new ArrayList<>();

        for (ChatModelMessage message : currentRequest.messages()) {
            if (message == null) {
                continue;
            }

            String role =
                    message.getRole();

            String content =
                    message.getContent();

            if (
                    message.getToolCalls() != null
                            && !message.getToolCalls().isEmpty()
            ) {
                continue;
            }

            if ("tool".equalsIgnoreCase(role)) {
                if (
                        content == null
                                || content.isBlank()
                ) {
                    continue;
                }

                String toolName =
                        message.getToolName();

                messages.add(
                        new ChatModelMessage(
                                "system",
                                """
                                [확보된 Tool Result]
                                tool=%s

                                %s
                                """.formatted(
                                        toolName == null
                                                ? "unknown"
                                                : toolName,
                                        content
                                )
                        )
                );

                continue;
            }

            if (
                    content == null
                            || content.isBlank()
            ) {
                continue;
            }

            messages.add(
                    new ChatModelMessage(
                            role,
                            content,
                            message.getImages()
                    )
            );
        }

        messages.add(
                new ChatModelMessage(
                        "system",
                        """
                        도구 실행 단계는 종료되었습니다.

                        위에서 확보한 결과를 바탕으로
                        사용자의 현재 질문에 대한 최종 답변을 작성하세요.

                        새로운 Tool Call, Tool Call JSON, 함수 호출 형식 또는
                        도구 사용 계획을 출력하지 마세요.

                        확인되지 않은 정보를 만들어내지 마세요.

                        정보가 부족하면 답변을 비우지 말고
                        확인된 범위와 확인하지 못한 범위를 구분해서 설명하세요.
                        """
                )
        );

        return new ChatModelRequest(
                currentRequest.modelType(),
                List.copyOf(
                        messages
                ),
                List.of()
        );
    }

    private String findOriginalUserQuestion(
            List<ChatModelMessage> messages
    ) {
        if (
                messages == null
                        || messages.isEmpty()
        ) {
            return "현재 질문에 대해 실제 source에서 확인된 내용만 설명하세요.";
        }

        for (
                int index = messages.size() - 1;
                index >= 0;
                index--
        ) {
            ChatModelMessage message =
                    messages.get(
                            index
                    );

            if (message == null) {
                continue;
            }

            if (
                    !"user".equalsIgnoreCase(
                            message.getRole()
                    )
            ) {
                continue;
            }

            String content =
                    message.getContent();

            if (
                    content == null
                            || content.isBlank()
            ) {
                continue;
            }

            return content;
        }

        return "현재 질문에 대해 실제 source에서 확인된 내용만 설명하세요.";
    }

    /**
     * 동일 Tool Call이 반복되었을 때 전체 조사를 종료하지 않는다.
     *
     * 동일 호출은 실행하지 않고 모델에게 이미 수행한 호출임을 알려
     * 다른 파일/심볼/검색어를 선택하거나, 충분한 근거가 있으면
     * 자연어 답변을 생성하도록 다음 round를 계속한다.
     */
    private String getExactSourcePath(
            ToolResult toolResult
    ) {
        if (
                toolResult == null
                        || toolResult.metadata() == null
        ) {
            return null;
        }

        Object exactSourcePath =
                toolResult.metadata()
                        .get(
                                "exactSourcePath"
                        );

        if (exactSourcePath == null) {
            return null;
        }

        String value =
                exactSourcePath.toString()
                        .trim();

        return value.isBlank()
                ? null
                : value;
    }

    private ChatModelRequest appendDuplicateToolCallReminder(
            ChatModelRequest currentRequest,
            List<ChatModelToolCall> duplicateToolCalls
    ) {
        List<ChatModelMessage> messages =
                new ArrayList<>(
                        currentRequest.messages()
                );

        String duplicateCalls =
                duplicateToolCalls == null
                        ? ""
                        : duplicateToolCalls.stream()
                        .map(toolCall ->
                                toolCall.name()
                                        + " "
                                        + String.valueOf(
                                        toolCall.arguments()
                                )
                        )
                        .collect(
                                java.util.stream.Collectors.joining(
                                        "\n"
                                )
                        );

        messages.add(
                new ChatModelMessage(
                        "system",
                        """
                        방금 요청한 Tool Call은 이미 동일한 arguments로 실행되었습니다.
                        동일 호출은 다시 실행하지 않습니다.
        
                        반복된 호출:
                        %s
        
                        아직 사용자 질문의 전체 흐름을 설명하는 데 필요한 연결 소스를
                        충분히 읽지 않았다면, 지금까지 읽은 파일을 다시 요청하지 마세요.
        
                        현재까지 확보한 검색 결과와 실제 source를 기준으로
                        사용자의 질문에서 아직 확인하지 못한 다음 연결 지점을 찾으세요.
        
                        예를 들어 호출 흐름을 조사 중이라면
                        현재 파일에서 실제 호출되는 함수, import, service, store,
                        API client, controller, component 등의 이름을 근거로
                        다음 SOURCE_PATH를 attachment_search로 찾고
                        필요한 경우 attachment_read_source로 실제 코드를 읽으세요.
        
                        파일명이나 함수명을 추측해서 만들지 마세요.
                        UUID/fileId를 SOURCE_PATH나 검색 query로 사용하지 마세요.
        
                        이미 실제 source를 충분히 읽어서 사용자 질문에 답할 수 있다면
                        추가 Tool Call 없이 자연어 답변을 작성하세요.
        
                        확인하지 않은 파일, 함수, API, endpoint는 답변에 포함하지 마세요.
                        """.formatted(
                                duplicateCalls
                        )
                )
        );

        return new ChatModelRequest(
                currentRequest.modelType(),
                List.copyOf(
                        messages
                ),
                currentRequest.tools()
        );
    }

    /**
     * Tool Calling이 여러 round 이어질 때 모델이 원래 사용자 질문을
     * 놓치지 않도록 현재 요청의 마지막 user message를 다시 명시한다.
     *
     * 이 reminder는 각 round의 모든 Tool Result 처리가 끝난 뒤
     * 한 번만 추가한다.
     */
    private ChatModelRequest appendOriginalQuestionReminder(
            ChatModelRequest currentRequest,
            ChatRequest request
    ) {
        if (
                currentRequest == null
                        || request == null
                        || request.getMessages() == null
                        || request.getMessages().isEmpty()
        ) {
            return currentRequest;
        }

        String originalQuestion =
                request.getMessages()
                        .stream()
                        .filter(message ->
                                message != null
                                        && "user".equalsIgnoreCase(
                                        message.getRole()
                                )
                        )
                        .reduce((first, second) -> second)
                        .map(message ->
                                message.getContent()
                        )
                        .map(String::trim)
                        .filter(content ->
                                !content.isBlank()
                        )
                        .orElse(null);

        if (originalQuestion == null) {
            return currentRequest;
        }

        List<ChatModelMessage> messages =
                new ArrayList<>(
                        currentRequest.messages()
                );

        messages.add(
                new ChatModelMessage(
                        "system",
                        """
                        [현재 사용자 요청]

                        %s

                        위 요청이 현재 Tool Calling 작업의 원래 목적입니다.

                        방금까지 확보한 Tool Result를 위 요청을 해결하기 위해 사용하세요.
                        Tool Result가 누적되더라도 원래 사용자 요청을 잊지 마세요.

                        첨부 ZIP 프로젝트 분석 중이라면 다음 원칙을 따르세요.

                        - attachment_search는 관련 파일의 SOURCE_PATH를 찾기 위한 탐색 도구입니다.
                        - 검색 결과에서 필요한 파일의 정확한 SOURCE_PATH를 확인했고
                          실제 함수 본문, API 호출, import, 상태 변경 또는 데이터 흐름을 확인해야 한다면
                          같은 검색을 반복하지 말고 attachment_read_source로 해당 파일을 읽으세요.
                        - 정확한 SOURCE_PATH를 이미 알고 있다면 attachment_search를 다시 호출하지 마세요.
                        - 다른 파일이 필요하지만 SOURCE_PATH를 모를 때만 attachment_search를 추가로 사용하세요.
                        - 충분한 근거가 확보되었다면 추가 도구 호출을 반복하지 말고
                          현재 사용자 요청에 대한 자연어 최종 답변을 작성하세요.
                        - Tool Result에서 확인되지 않은 구현은 추측하지 마세요.
                        """.formatted(
                                originalQuestion
                        )
                )
        );

        return new ChatModelRequest(
                currentRequest.modelType(),
                List.copyOf(
                        messages
                ),
                currentRequest.tools()
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

    private String findSelectedUnreadSourcePath(
            ToolResult toolResult,
            Set<String> executedToolCalls
    ) {
        if (
                toolResult == null
                        || toolResult.metadata() == null
                        || toolResult.metadata().isEmpty()
        ) {
            return null;
        }

        Object selectedSourcePathValue =
                toolResult.metadata()
                        .get(
                                "selectedSourcePath"
                        );

        if (selectedSourcePathValue == null) {
            return null;
        }

        String sourcePath =
                selectedSourcePathValue.toString()
                        .trim();

        if (sourcePath.isBlank()) {
            return null;
        }

        String readSourceToolCallKey =
                createToolCallKey(
                        ATTACHMENT_READ_SOURCE_TOOL_NAME,
                        Map.of(
                                "sourcePath",
                                sourcePath
                        )
                );

        if (
                executedToolCalls != null
                        && executedToolCalls.contains(
                        readSourceToolCallKey
                )
        ) {
            return null;
        }

        return sourcePath;
    }

    private String findBestUnreadSourcePath(
            ToolResult toolResult,
            String searchQuery,
            Set<String> executedToolCalls
    ) {
        if (
                toolResult == null
                        || toolResult.metadata() == null
                        || toolResult.metadata().isEmpty()
        ) {
            return null;
        }

        Object sourcePathsValue =
                toolResult.metadata()
                        .get(
                                "sourcePaths"
                        );

        if (!(sourcePathsValue instanceof List<?> sourcePaths)) {
            return null;
        }

        List<String> unreadSourcePaths =
                new ArrayList<>();

        for (Object sourcePathValue : sourcePaths) {
            if (sourcePathValue == null) {
                continue;
            }

            String sourcePath =
                    sourcePathValue.toString()
                            .trim();

            if (sourcePath.isBlank()) {
                continue;
            }

            String readSourceToolCallKey =
                    createToolCallKey(
                            ATTACHMENT_READ_SOURCE_TOOL_NAME,
                            Map.of(
                                    "sourcePath",
                                    sourcePath
                            )
                    );

            if (
                    executedToolCalls != null
                            && executedToolCalls.contains(
                            readSourceToolCallKey
                    )
            ) {
                continue;
            }

            unreadSourcePaths.add(
                    sourcePath
            );
        }

        if (unreadSourcePaths.isEmpty()) {
            return null;
        }

        String normalizedQuery =
                normalizeSearchQuery(
                        searchQuery
                );

        if (normalizedQuery == null) {
            return unreadSourcePaths.getFirst();
        }

        /*
         * 1순위: 검색어 전체가 파일명과 정확히 일치
         * 예) Sidebar.vue -> .../components/Sidebar.vue
         */
        for (String sourcePath : unreadSourcePaths) {
            String fileName =
                    getFileName(
                            sourcePath
                    );

            if (fileName.equalsIgnoreCase(normalizedQuery)) {
                return sourcePath;
            }
        }

        /*
         * 2순위: SOURCE_PATH 자체가 검색어로 끝남
         * 예) components/Sidebar.vue -> .../components/Sidebar.vue
         */
        String normalizedQueryPath =
                normalizedQuery.replace(
                        '\\',
                        '/'
                );

        for (String sourcePath : unreadSourcePaths) {
            String normalizedSourcePath =
                    sourcePath.replace(
                            '\\',
                            '/'
                    );

            if (
                    normalizedSourcePath.equalsIgnoreCase(
                            normalizedQueryPath
                    )
                            || normalizedSourcePath.toLowerCase()
                            .endsWith(
                                    "/"
                                            + normalizedQueryPath.toLowerCase()
                            )
            ) {
                return sourcePath;
            }
        }

        /*
         * 3순위: 검색어가 확장자를 포함한 파일명 형태라면
         * 같은 확장자의 파일 중 파일명 부분이 가장 가까운 것을 선택.
         */
        int queryExtensionIndex =
                normalizedQuery.lastIndexOf(
                        '.'
                );

        if (
                queryExtensionIndex > 0
                        && queryExtensionIndex < normalizedQuery.length() - 1
        ) {
            String queryBaseName =
                    normalizedQuery.substring(
                            0,
                            queryExtensionIndex
                    );

            String queryExtension =
                    normalizedQuery.substring(
                            queryExtensionIndex + 1
                    );

            for (String sourcePath : unreadSourcePaths) {
                String fileName =
                        getFileName(
                                sourcePath
                        );

                int fileExtensionIndex =
                        fileName.lastIndexOf(
                                '.'
                        );

                if (
                        fileExtensionIndex <= 0
                                || fileExtensionIndex >= fileName.length() - 1
                ) {
                    continue;
                }

                String fileBaseName =
                        fileName.substring(
                                0,
                                fileExtensionIndex
                        );

                String fileExtension =
                        fileName.substring(
                                fileExtensionIndex + 1
                        );

                if (
                        fileExtension.equalsIgnoreCase(
                                queryExtension
                        )
                                && (
                                fileBaseName.equalsIgnoreCase(
                                        queryBaseName
                                )
                                        || fileBaseName.toLowerCase()
                                        .contains(
                                                queryBaseName.toLowerCase()
                                        )
                                        || queryBaseName.toLowerCase()
                                        .contains(
                                                fileBaseName.toLowerCase()
                                        )
                        )
                ) {
                    return sourcePath;
                }
            }
        }

        /*
         * 4순위: 파일명에 검색어가 포함되는 경로.
         */
        String normalizedQueryLowerCase =
                normalizedQuery.toLowerCase();

        for (String sourcePath : unreadSourcePaths) {
            String fileName =
                    getFileName(
                            sourcePath
                    );

            if (
                    fileName.toLowerCase()
                            .contains(
                                    normalizedQueryLowerCase
                            )
            ) {
                return sourcePath;
            }
        }

        /*
         * 정확한 파일 매칭이 없으면 RAG 검색 결과의 기존 랭킹을 유지한다.
         */
        return unreadSourcePaths.getFirst();
    }

    private String getSearchQuery(
            ChatModelToolCall toolCall
    ) {
        if (
                toolCall == null
                        || toolCall.arguments() == null
        ) {
            return null;
        }

        Object query =
                toolCall.arguments()
                        .get(
                                "query"
                        );

        if (query == null) {
            return null;
        }

        String value =
                query.toString()
                        .trim();

        return value.isBlank()
                ? null
                : value;
    }

    private String normalizeSearchQuery(
            String searchQuery
    ) {
        if (searchQuery == null) {
            return null;
        }

        String normalized =
                searchQuery.trim();

        if (normalized.isBlank()) {
            return null;
        }

        while (
                normalized.startsWith("\"")
                        && normalized.endsWith("\"")
                        && normalized.length() > 1
        ) {
            normalized =
                    normalized.substring(
                                    1,
                                    normalized.length() - 1
                            )
                            .trim();
        }

        return normalized.isBlank()
                ? null
                : normalized;
    }

    private String getFileName(
            String sourcePath
    ) {
        String normalized =
                sourcePath.replace(
                        '\\',
                        '/'
                );

        int separatorIndex =
                normalized.lastIndexOf(
                        '/'
                );

        if (separatorIndex < 0) {
            return normalized;
        }

        return normalized.substring(
                separatorIndex + 1
        );
    }

    private boolean wasToolExecutedSuccessfully(
            List<ChatModelToolCall> toolCalls,
            List<ToolResult> toolResults,
            String toolName
    ) {
        if (
                toolCalls == null
                        || toolResults == null
                        || toolName == null
        ) {
            return false;
        }

        int size =
                Math.min(
                        toolCalls.size(),
                        toolResults.size()
                );

        for (int index = 0; index < size; index++) {
            ChatModelToolCall toolCall =
                    toolCalls.get(
                            index
                    );

            ToolResult toolResult =
                    toolResults.get(
                            index
                    );

            if (
                    toolName.equals(
                            toolCall.name()
                    )
                            && toolResult.success()
            ) {
                return true;
            }
        }

        return false;
    }

    private ChatModelTool findTool(
            List<ChatModelTool> tools,
            String toolName
    ) {
        if (
                tools == null
                        || tools.isEmpty()
                        || toolName == null
        ) {
            return null;
        }

        return tools.stream()
                .filter(tool ->
                        toolName.equals(
                                tool.name()
                        )
                )
                .findFirst()
                .orElse(
                        null
                );
    }

    private String createToolCallKey(
            String toolName,
            Map<String, Object> arguments
    ) {
        if (
                arguments == null
                        || arguments.isEmpty()
        ) {
            return toolName + ":{}";
        }

        String normalizedArguments =
                arguments.entrySet()
                        .stream()
                        .sorted(
                                Map.Entry.comparingByKey()
                        )
                        .map(entry ->
                                entry.getKey()
                                        + "="
                                        + String.valueOf(
                                        entry.getValue()
                                )
                        )
                        .reduce(
                                (left, right) ->
                                        left + "&" + right
                        )
                        .orElse("");

        return toolName
                + ":"
                + normalizedArguments;
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

    private ChatModelRequest appendSourceGroundingCompletionCheck(
            ChatModelRequest currentRequest,
            ChatRequest request
    ) {
        if (
                currentRequest == null
                        || request == null
                        || request.getMessages() == null
                        || request.getMessages().isEmpty()
        ) {
            return currentRequest;
        }

        String originalQuestion =
                request.getMessages()
                        .stream()
                        .filter(message ->
                                message != null
                                        && "user".equalsIgnoreCase(
                                        message.getRole()
                                )
                        )
                        .reduce((first, second) -> second)
                        .map(message ->
                                message.getContent()
                        )
                        .map(String::trim)
                        .filter(content ->
                                !content.isBlank()
                        )
                        .orElse(null);

        if (originalQuestion == null) {
            return currentRequest;
        }

        List<ChatModelMessage> messages =
                new ArrayList<>(
                        currentRequest.messages()
                );

        messages.add(
                new ChatModelMessage(
                        "system",
                        """
                        [SOURCE 탐색 완료 여부 확인]
    
                        원래 사용자 요청:
    
                        %s
    
                        실제 source를 일부 확보했지만,
                        지금 바로 최종 답변을 작성하지 마세요.
    
                        먼저 현재까지 attachment_read_source로 실제 읽은 source만으로
                        위 사용자 요청 전체에 답할 수 있는지 확인하세요.
    
                        특히 사용자가 다음과 같은 연결 관계를 요구한 경우에는
                        질문에서 요구한 흐름의 각 단계가 실제 source로 확인되었는지 점검하세요.
    
                        - 함수 → 함수
                        - 컴포넌트 → 서비스
                        - 프론트엔드 → API
                        - API → Controller
                        - Controller → Service
                        - Service → Repository
                        - 호출 → 상태 변경
                        - 요청 → 처리 → 응답
                        - 파일 업로드 → 분석 → 결과 전달
                        - 이벤트 발생 → 처리 → UI 반영
    
                        아직 확인하지 않은 단계가 있다면
                        절대로 구현을 추측해서 답변하지 마세요.
    
                        대신 현재 읽은 source에서 확인된 클래스명, 함수명,
                        import, 호출 대상, endpoint 또는 symbol을 단서로
                        attachment_search를 사용해 다음 source를 찾고,
                        정확한 SOURCE_PATH를 찾으면 attachment_read_source로 읽으세요.
    
                        이미 읽은 SOURCE_PATH를 다시 읽지 마세요.
                        fileId 또는 UUID를 attachment_search query로 사용하지 마세요.
    
                        반대로 원래 질문 전체에 답하는 데 필요한 구현이
                        현재 읽은 source만으로 실제 확인되었다면
                        추가 Tool Call 없이 답변 생성을 종료하세요.
    
                        확인되지 않은 파일이나 구현을
                        "일반적으로", "추정", "예상", "가능성" 등의 표현으로
                        보완하지 마세요.
                        """.formatted(
                                originalQuestion
                        )
                )
        );

        return new ChatModelRequest(
                currentRequest.modelType(),
                List.copyOf(
                        messages
                ),
                currentRequest.tools()
        );
    }
}