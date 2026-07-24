package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.entity.ChatMessage;
import com.agent.aiagent.domain.chat.entity.ChatRoom;
import com.agent.aiagent.domain.chat.repository.ChatMessageRepository;
import com.agent.aiagent.domain.chat.repository.ChatRoomRepository;
import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.file.service.ChatFileService;
import com.agent.aiagent.domain.file.service.FilePromptBuilder;
import com.agent.aiagent.domain.rag.service.RagMultiQueryService;
import com.agent.aiagent.domain.rag.service.RagQueryRewriteService;
import com.agent.aiagent.infra.ollama.OllamaClient;
import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final long SSE_TIMEOUT = 300_000L;
    private static final String USER_ROLE = "user";
    private static final String ASSISTANT_ROLE = "assistant";

    private final OllamaClient ollamaClient;
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

    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String roomId = request.getRoomId();
        String userContent = getLastUserMessageContent(request);

        if (!request.isRegenerate()) {
            saveUserMessage(roomId, userContent);
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

        Disposable disposable = ollamaClient
                .chat(
                        ollamaRequestData.model(),
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
                                    saveInterruptedAssistantMessage(
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
                                    saveInterruptedAssistantMessage(
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
                                        replaceLastAssistantMessage(
                                                roomId,
                                                assistantContent.toString()
                                        );
                                    } else {
                                        saveAssistantMessage(
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
                saveInterruptedAssistantMessage(
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
                saveInterruptedAssistantMessage(
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
                saveInterruptedAssistantMessage(
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

    private void replaceLastAssistantMessage(
            String roomId,
            String content
    ) {
        if (content == null || content.isBlank()) {
            log.warn(
                    "교체할 AI 응답이 비어 있습니다. roomId={}",
                    roomId
            );

            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            ChatRoom chatRoom = findRoom(roomId);

            chatMessageRepository
                    .findFirstByRoomIdAndRoleOrderByCreatedAtDesc(
                            roomId,
                            ASSISTANT_ROLE
                    )
                    .ifPresent(chatMessageRepository::delete);

            ChatMessage newAssistantMessage = new ChatMessage(
                    chatRoom,
                    ASSISTANT_ROLE,
                    content
            );

            chatMessageRepository.save(newAssistantMessage);
            chatRoom.touch();
        });
    }

    private void saveInterruptedAssistantMessage(
            ChatRequest request,
            String roomId,
            StringBuilder assistantContent,
            AtomicBoolean responseSaved
    ) {
        if (request.isRegenerate()) {
            return;
        }

        if (!responseSaved.compareAndSet(false, true)) {
            return;
        }

        String content = assistantContent.length() > 0
                ? assistantContent.toString()
                : "응답이 중단되었습니다.";

        saveAssistantMessage(
                roomId,
                content
        );

        log.info(
                "중단된 AI 응답을 저장했습니다. roomId={}, contentLength={}",
                roomId,
                content.length()
        );
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

    private void saveUserMessage(
            String roomId,
            String content
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatRoom chatRoom = findRoom(roomId);

            boolean isFirstUserMessage =
                    !chatMessageRepository.existsByRoomIdAndRole(
                            roomId,
                            USER_ROLE
                    );

            ChatMessage chatMessage = new ChatMessage(
                    chatRoom,
                    USER_ROLE,
                    content
            );

            chatMessageRepository.save(chatMessage);

            if (
                    isFirstUserMessage
                            && "새 채팅".equals(chatRoom.getTitle())
            ) {
                chatRoom.changeTitle(
                        createRoomTitle(content)
                );
            }

            chatRoom.touch();
        });
    }

    private String createRoomTitle(
            String content
    ) {
        String normalizedContent = content
                .replaceAll("\\s+", " ")
                .trim();

        if (normalizedContent.length() <= 25) {
            return normalizedContent;
        }

        return normalizedContent.substring(0, 25) + "...";
    }

    private void saveAssistantMessage(
            String roomId,
            String content
    ) {
        if (content == null || content.isBlank()) {
            log.warn(
                    "저장할 AI 응답이 비어 있습니다. roomId={}",
                    roomId
            );

            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            ChatRoom chatRoom = findRoom(roomId);

            ChatMessage chatMessage = new ChatMessage(
                    chatRoom,
                    ASSISTANT_ROLE,
                    content
            );

            chatMessageRepository.save(chatMessage);
            chatRoom.touch();
        });
    }

    private ChatRoom findRoom(String roomId) {
        return chatRoomRepository
                .findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "채팅방을 찾을 수 없습니다."
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
                        .filter(this::isImage)
                        .toList();

        List<String> documentFileIds =
                chatFiles.stream()
                        .filter(file ->
                                !isImage(file)
                        )
                        .map(ChatFile::getId)
                        .toList();

        List<String> encodedImages =
                imageFiles.stream()
                        .map(this::encodeImage)
                        .toList();

        List<OllamaChatMessage> messages =
                createOllamaMessages(
                        request.getRoomId(),
                        request.isRegenerate(),
                        documentFileIds,
                        encodedImages
                );

        String model =
                encodedImages.isEmpty()
                        ? OllamaClient.MODEL_TEXT
                        : OllamaClient.MODEL_VISION;

        log.info(
                "Ollama 요청 생성 완료. roomId={}, model={}, documentCount={}, imageCount={}",
                request.getRoomId(),
                model,
                documentFileIds.size(),
                encodedImages.size()
        );

        return new OllamaRequestData(
                model,
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

    private boolean isImage(
            ChatFile file
    ) {

        return switch (file.getExtension()) {
            case "png",
                 "jpg",
                 "jpeg",
                 "gif",
                 "webp" -> true;
            default -> false;
        };
    }

    private String encodeImage(
            ChatFile file
    ) {
        try {
            byte[] bytes =
                    Files.readAllBytes(
                            Path.of(
                                    file.getStoredPath()
                            )
                    );

            return Base64.getEncoder()
                    .encodeToString(
                            bytes
                    );
        } catch (IOException exception) {
            log.error(
                    "이미지 파일을 읽지 못했습니다. fileId={}, storedPath={}",
                    file.getId(),
                    file.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "이미지 파일을 읽지 못했습니다.",
                    exception
            );
        }
    }

    private record OllamaRequestData(
            String model,
            List<OllamaChatMessage> messages
    ) {
    }
}