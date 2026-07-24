package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.repository.ChatMessageRepository;
import com.agent.aiagent.domain.chat.repository.ChatRoomRepository;
import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.file.service.ChatFileService;
import com.agent.aiagent.domain.file.service.FilePromptBuilder;
import com.agent.aiagent.domain.rag.service.RagMultiQueryService;
import com.agent.aiagent.domain.rag.service.RagQueryRewriteService;
import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final long SSE_TIMEOUT = 300_000L;
    private static final String USER_ROLE = "user";
    private static final String ASSISTANT_ROLE = "assistant";

    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TransactionTemplate transactionTemplate;
    private final FilePromptBuilder filePromptBuilder;
    private final RagQueryRewriteService ragQueryRewriteService;
    private final RagMultiQueryService ragMultiQueryService;
    private final ChatFileService chatFileService;
    private final ChatFileRepository chatFileRepository;
    private final ConversationSummaryService conversationSummaryService;
    private final ChatImageEncoder chatImageEncoder;
    private final ChatPersistenceService chatPersistenceService;

    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String roomId = request.getRoomId();
        String userContent = getLastUserMessageContent(request);

        if (!request.isRegenerate()) {
            chatPersistenceService.saveUserMessage(
                    roomId,
                    userContent
            );
        }

        conversationSummaryService.refreshSummaryIfNeeded(
                roomId,
                request.isRegenerate()
        );

        StringBuilder assistantContent = new StringBuilder();

        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean terminated = new AtomicBoolean(false);
        AtomicBoolean responseSaved = new AtomicBoolean(false);

        OllamaRequestData ollamaRequestData =
                createOllamaRequestData(
                        request
                );

        Disposable disposable = chatModelProvider
                .chat(
                        ollamaRequestData.modelType(),
                        ollamaRequestData.messages()
                )
                .subscribe(
                        response -> {
                            if (terminated.get()) {
                                return;
                            }

                            if (response.getMessage() == null) {
                                return;
                            }

                            String chunk = response
                                    .getMessage()
                                    .getContent();

                            if (chunk == null || chunk.isEmpty()) {
                                return;
                            }

                            assistantContent.append(chunk);

                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .name("message")
                                                .data(
                                                        objectMapper.writeValueAsString(
                                                                chunk
                                                        )
                                                )
                                );
                            } catch (AsyncRequestNotUsableException exception) {
                                if (terminated.compareAndSet(false, true)) {
                                    chatPersistenceService.saveInterruptedAssistantMessage(
                                            request,
                                            roomId,
                                            assistantContent,
                                            responseSaved
                                    );
                                }

                                log.info(
                                        "클라이언트가 SSE 연결을 종료했습니다. roomId={}",
                                        roomId
                                );
                            } catch (IOException exception) {
                                if (terminated.compareAndSet(false, true)) {
                                    chatPersistenceService.saveInterruptedAssistantMessage(
                                            request,
                                            roomId,
                                            assistantContent,
                                            responseSaved
                                    );
                                }

                                log.info(
                                        "SSE 응답 전송 중 연결이 종료되었습니다. roomId={}",
                                        roomId
                                );
                            } catch (Exception exception) {
                                terminated.set(true);

                                log.error(
                                        "Ollama 응답 전송 중 오류가 발생했습니다. roomId={}",
                                        roomId,
                                        exception
                                );

                                emitter.completeWithError(exception);
                            }
                        },
                        error -> {
                            if (
                                    terminated.get()
                                            || !completed.compareAndSet(false, true)
                            ) {
                                return;
                            }

                            log.error(
                                    "Ollama 스트리밍 호출 중 오류가 발생했습니다. roomId={}",
                                    roomId,
                                    error
                            );

                            emitter.completeWithError(error);
                        },
                        () -> {
                            if (
                                    terminated.get()
                                            || !completed.compareAndSet(false, true)
                            ) {
                                return;
                            }

                            try {
                                if (responseSaved.compareAndSet(false, true)) {
                                    if (request.isRegenerate()) {
                                        chatPersistenceService.replaceLastAssistantMessage(
                                                roomId,
                                                assistantContent.toString()
                                        );
                                    } else {
                                        chatPersistenceService.saveAssistantMessage(
                                                roomId,
                                                assistantContent.toString()
                                        );
                                    }
                                }

                                emitter.send(
                                        SseEmitter.event()
                                                .name("done")
                                                .data("")
                                );

                                emitter.complete();
                            } catch (AsyncRequestNotUsableException exception) {
                                terminated.set(true);

                                log.info(
                                        "완료 응답 전송 전에 클라이언트 연결이 종료되었습니다. roomId={}",
                                        roomId
                                );
                            } catch (IOException exception) {
                                terminated.set(true);

                                log.info(
                                        "완료 응답 전송 중 연결이 종료되었습니다. roomId={}",
                                        roomId
                                );
                            } catch (Exception exception) {
                                terminated.set(true);

                                log.error(
                                        "AI 응답 저장 또는 SSE 완료 처리 중 오류가 발생했습니다. roomId={}",
                                        roomId,
                                        exception
                                );

                                emitter.completeWithError(exception);
                            }
                        }
                );

        emitter.onCompletion(() -> {
            if (
                    !completed.get()
                            && terminated.compareAndSet(false, true)
            ) {
                chatPersistenceService.saveInterruptedAssistantMessage(
                        request,
                        roomId,
                        assistantContent,
                        responseSaved
                );
            }

            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        });

        emitter.onTimeout(() -> {
            if (terminated.compareAndSet(false, true)) {
                chatPersistenceService.saveInterruptedAssistantMessage(
                        request,
                        roomId,
                        assistantContent,
                        responseSaved
                );
            }

            completed.compareAndSet(false, true);

            log.warn(
                    "SSE 스트리밍 요청 시간이 초과되었습니다. roomId={}",
                    roomId
            );

            if (!disposable.isDisposed()) {
                disposable.dispose();
            }

            emitter.complete();
        });

        emitter.onError(error -> {
            if (terminated.compareAndSet(false, true)) {
                chatPersistenceService.saveInterruptedAssistantMessage(
                        request,
                        roomId,
                        assistantContent,
                        responseSaved
                );
            }

            if (!disposable.isDisposed()) {
                disposable.dispose();
            }

            if (
                    error instanceof AsyncRequestNotUsableException
                            || error instanceof IOException
            ) {
                log.info(
                        "클라이언트 연결 종료로 SSE 오류가 발생했습니다. roomId={}",
                        roomId
                );

                return;
            }

            log.error(
                    "SSE 스트리밍 오류가 발생했습니다. roomId={}",
                    roomId,
                    error
            );
        });

        return emitter;
    }

    private String getLastUserMessageContent(ChatRequest request) {
        return request.getMessages()
                .stream()
                .filter(message ->
                        USER_ROLE.equalsIgnoreCase(message.getRole())
                )
                .reduce((first, second) -> second)
                .map(message -> message.getContent())
                .filter(content -> !content.isBlank())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "사용자 메시지가 없습니다."
                ));
    }

    private OllamaRequestData createOllamaRequestData(
            ChatRequest request
    ) {
        List<ChatFile> chatFiles =
                findRequestFiles(
                        request
                );

        List<ChatFile> imageFiles =
                chatFiles.stream()
                        .filter(chatImageEncoder::isImage)
                        .toList();

        List<String> documentFileIds =
                chatFiles.stream()
                        .filter(file ->
                                !chatImageEncoder.isImage(file)
                        )
                        .map(ChatFile::getId)
                        .toList();

        List<String> encodedImages =
                imageFiles.stream()
                        .map(chatImageEncoder::encode)
                        .toList();

        List<OllamaChatMessage> messages =
                createOllamaMessages(
                        request.getRoomId(),
                        request.isRegenerate(),
                        documentFileIds,
                        encodedImages
                );

        ChatModelType modelType =
                encodedImages.isEmpty()
                        ? ChatModelType.TEXT
                        : ChatModelType.VISION;

        log.info(
                "채팅 모델 요청 생성 완료. roomId={}, modelType={}, documentCount={}, imageCount={}",
                request.getRoomId(),
                modelType,
                documentFileIds.size(),
                encodedImages.size()
        );

        return new OllamaRequestData(
                modelType,
                messages
        );
    }

    private List<ChatFile> findRequestFiles(
            ChatRequest request
    ) {
        if (
                request.getFileIds() == null
                        || request.getFileIds().isEmpty()
        ) {
            List<ChatFile> roomFiles =
                    chatFileRepository.findAllByRoomIdOrderByCreatedAtAsc(
                            request.getRoomId()
                    );

            return roomFiles;
        }

        return chatFileService.findFiles(
                request.getRoomId(),
                request.getFileIds()
        );
    }

    private List<OllamaChatMessage> createOllamaMessages(
            String roomId,
            boolean regenerate,
            List<String> documentFileIds,
            List<String> encodedImages
    ) {
        List<OllamaChatMessage> messages =
                conversationSummaryService.createConversationContext(
                        roomId,
                        regenerate
                );

        if (
                documentFileIds.isEmpty()
                        && encodedImages.isEmpty()
        ) {
            return messages;
        }

        for (
                int index = messages.size() - 1;
                index >= 0;
                index--
        ) {
            OllamaChatMessage message =
                    messages.get(index);

            if (!USER_ROLE.equalsIgnoreCase(message.getRole())) {
                continue;
            }

            String content =
                    message.getContent();

            if (!documentFileIds.isEmpty()) {
                String searchQuestion =
                        ragQueryRewriteService.rewrite(
                                messages,
                                content
                        );

                List<String> searchQuestions =
                        ragMultiQueryService.generate(
                                searchQuestion
                        );

                content =
                        filePromptBuilder.build(
                                roomId,
                                documentFileIds,
                                content,
                                searchQuestion,
                                searchQuestions
                        );
            }

            List<String> images =
                    encodedImages.isEmpty()
                            ? null
                            : encodedImages;

            messages.set(
                    index,
                    new OllamaChatMessage(
                            message.getRole(),
                            content,
                            images
                    )
            );

            break;
        }

        log.info(
                "대화 메모리 기반 Ollama 메시지 생성 완료. roomId={}, messageCount={}, regenerate={}",
                roomId,
                messages.size(),
                regenerate
        );

        return messages;
    }

    private record OllamaRequestData(
            ChatModelType modelType,
            List<OllamaChatMessage> messages
    ) {
    }
}