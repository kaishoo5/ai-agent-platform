package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.entity.ChatMessage;
import com.agent.aiagent.domain.chat.entity.ChatRoom;
import com.agent.aiagent.domain.chat.repository.ChatMessageRepository;
import com.agent.aiagent.domain.chat.repository.ChatRoomRepository;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummaryService {

    private static final int RECENT_MESSAGE_COUNT = 8;
    private static final int SUMMARY_BATCH_SIZE = 6;

    private static final String SYSTEM_ROLE = "system";
    private static final String ASSISTANT_ROLE = "assistant";

    private final ChatModelProvider chatModelProvider;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TransactionTemplate transactionTemplate;

    public void refreshSummaryIfNeeded(
            String roomId,
            boolean regenerate
    ) {
        SummaryTarget summaryTarget =
                createSummaryTarget(
                        roomId,
                        regenerate
                );

        if (summaryTarget == null) {
            return;
        }

        try {
            String newSummary =
                    generateSummary(
                            summaryTarget.existingSummary(),
                            summaryTarget.messages()
                    );

            if (!StringUtils.hasText(newSummary)) {
                log.warn(
                        "대화 요약 결과가 비어 있어 저장하지 않습니다. roomId={}",
                        roomId
                );

                return;
            }

            saveSummary(
                    roomId,
                    newSummary
            );

            log.info(
                    "대화 요약 갱신 완료. roomId={}, summarizedMessageCount={}, summaryLength={}",
                    roomId,
                    summaryTarget.messages().size(),
                    newSummary.length()
            );
        } catch (Exception exception) {
            log.warn(
                    "대화 요약 갱신에 실패했습니다. 기존 컨텍스트로 채팅을 진행합니다. roomId={}",
                    roomId,
                    exception
            );
        }
    }

    public List<ChatModelMessage> createConversationContext(
            String roomId,
            boolean regenerate
    ) {
        ChatRoom chatRoom =
                findRoom(
                        roomId
                );

        List<ChatMessage> chatMessages =
                new ArrayList<>(
                        chatMessageRepository
                                .findAllByRoomIdOrderByCreatedAtAsc(
                                        roomId
                                )
                );

        if (regenerate) {
            removeLastAssistantMessage(
                    chatMessages
            );
        }

        int recentStartIndex =
                Math.max(
                        0,
                        chatMessages.size() - RECENT_MESSAGE_COUNT
                );

        List<ChatMessage> recentMessages =
                chatMessages.subList(
                        recentStartIndex,
                        chatMessages.size()
                );

        List<ChatModelMessage> contextMessages =
                new ArrayList<>();

        contextMessages.add(
                new ChatModelMessage(
                        SYSTEM_ROLE,
                        createSystemPrompt(),
                        null
                )
        );

        if (StringUtils.hasText(chatRoom.getSummary())) {
            contextMessages.add(
                    new ChatModelMessage(
                            SYSTEM_ROLE,
                            createSummaryMemoryPrompt(
                                    chatRoom.getSummary()
                            ),
                            null
                    )
            );
        }

        recentMessages.stream()
                .map(chatMessage ->
                        new ChatModelMessage(
                                chatMessage.getRole().toLowerCase(
                                        Locale.ROOT
                                ),
                                chatMessage.getContent(),
                                null
                        )
                )
                .forEach(contextMessages::add);

        log.info(
                "대화 메모리 컨텍스트 생성 완료. roomId={}, summaryIncluded={}, recentMessageCount={}, contextMessageCount={}, regenerate={}",
                roomId,
                StringUtils.hasText(chatRoom.getSummary()),
                recentMessages.size(),
                contextMessages.size(),
                regenerate
        );

        return contextMessages;
    }

    private SummaryTarget createSummaryTarget(
            String roomId,
            boolean regenerate
    ) {
        ChatRoom chatRoom =
                findRoom(
                        roomId
                );

        List<ChatMessage> chatMessages =
                new ArrayList<>(
                        chatMessageRepository
                                .findAllByRoomIdOrderByCreatedAtAsc(
                                        roomId
                                )
                );

        if (regenerate) {
            removeLastAssistantMessage(
                    chatMessages
            );
        }

        int summaryEndIndex =
                chatMessages.size() - RECENT_MESSAGE_COUNT;

        if (summaryEndIndex <= 0) {
            return null;
        }

        LocalDateTime summaryUpdatedAt =
                chatRoom.getSummaryUpdatedAt();

        List<ChatMessage> messagesToSummarize =
                chatMessages.subList(
                                0,
                                summaryEndIndex
                        )
                        .stream()
                        .filter(chatMessage ->
                                summaryUpdatedAt == null
                                        || chatMessage.getCreatedAt()
                                        .isAfter(summaryUpdatedAt)
                        )
                        .toList();

        if (messagesToSummarize.size() < SUMMARY_BATCH_SIZE) {
            log.debug(
                    "대화 요약 갱신 조건 미충족. roomId={}, pendingMessageCount={}, requiredMessageCount={}",
                    roomId,
                    messagesToSummarize.size(),
                    SUMMARY_BATCH_SIZE
            );

            return null;
        }

        return new SummaryTarget(
                chatRoom.getSummary(),
                messagesToSummarize
        );
    }

    private String generateSummary(
            String existingSummary,
            List<ChatMessage> messages
    ) {
        String existingSummaryContent =
                StringUtils.hasText(existingSummary)
                        ? existingSummary
                        : "기존 요약 없음";

        String prompt =
                """
                아래의 기존 대화 요약과 새 대화를 합쳐서
                하나의 최신 대화 메모리로 다시 작성하세요.

                [기존 대화 요약]
                %s

                [새로 추가할 대화]
                %s

                요약 규칙:
                1. 사용자의 이름, 직업, 기술 스택, 취향, 목표와 같은 중요 정보를 보존하세요.
                2. 현재 진행 중인 작업과 이미 완료된 작업을 구분하세요.
                3. 사용자가 요청한 조건과 결정된 구현 방식을 보존하세요.
                4. 일시적인 인사, 반복 표현, 불필요한 감탄사는 제외하세요.
                5. 사실을 새로 만들거나 추측하지 마세요.
                6. 이후 대화에서 참조하기 쉽도록 간결하고 구체적으로 작성하세요.
                7. 요약 내용만 출력하고 설명이나 머리말은 출력하지 마세요.
                """.formatted(
                        existingSummaryContent,
                        buildConversationText(
                                messages
                        )
                );

        return chatModelProvider.chatOnce(
                new ChatModelRequest(
                        ChatModelType.TEXT,
                        List.of(
                                new ChatModelMessage(
                                        SYSTEM_ROLE,
                                        """
                                        당신은 장기 대화를 압축하는 Conversation Memory 요약기입니다.
                                        기존 기억과 새로운 대화를 병합하여 중요한 사실과 진행 상황을 보존합니다.
                                        """,
                                        null
                                ),
                                new ChatModelMessage(
                                        "user",
                                        prompt,
                                        null
                                )
                        ),
                        List.of()
                )
        ).content();
    }

    private String buildConversationText(
            List<ChatMessage> messages
    ) {
        StringBuilder builder =
                new StringBuilder();

        for (ChatMessage message : messages) {
            builder.append(
                            message.getRole()
                    )
                    .append(": ")
                    .append(
                            message.getContent()
                    )
                    .append("\n\n");
        }

        return builder.toString()
                .trim();
    }

    private String createSystemPrompt() {
        LocalDate currentDate =
                LocalDate.now();

        return """
            현재 날짜는 %s입니다.

            현재 날짜와 관련된 질문에서는 반드시 위 날짜를 기준으로 판단하세요.
            모델의 학습 데이터에 포함된 과거 날짜나 기존 지식을 현재 날짜로 간주하지 마세요.

            "현재", "오늘", "요즘", "요새", "최근", "최신", "방영 중" 등
            시점에 따라 답이 달라지는 질문은 최신 정보가 필요한 질문으로 판단하세요.

            최신 정보가 필요한 경우 반드시 사용 가능한 web_search 도구를 사용하세요.

            web_search 검색어를 생성할 때도 반드시 현재 날짜를 기준으로 작성하세요.
            사용자가 특정 과거 연도를 명시하지 않았다면 과거 연도를 임의로 검색어에 추가하지 마세요.

            예를 들어 현재 날짜가 2026년이라면
            "요즘 한국 드라마", "최근 한국 드라마", "현재 방영 중인 한국 드라마"와 같은 질문을
            2023년, 2024년, 2025년 등의 과거 연도 검색어로 변경하지 마세요.

            필요한 경우 현재 연도인 %d년을 검색어에 포함하세요.

            web_search 도구를 사용할 수 있는데도
            웹 검색 기능을 사용할 수 없다고 답변하지 마세요.

            검색 결과로 확인할 수 없는 사실은 임의로 만들거나 추측하지 마세요.

            답변은 표준 Markdown 형식으로 작성하세요.
            제목, 목록, 강조, 표, 코드 블록 등은 Markdown 문법을 사용하세요.

            HTML 태그는 사용하지 마세요.
            특히 <span>, <div>, <font>, <br> 등의 HTML 태그와
            style 속성을 사용한 색상 또는 스타일 표현을 사용하지 마세요.

            특정 텍스트를 강조해야 하는 경우 HTML 대신
            Markdown의 **굵게**, *기울임*, `인라인 코드` 등의 문법을 사용하세요.
            """.formatted(
                currentDate,
                currentDate.getYear()
        );
    }

    private String createSummaryMemoryPrompt(
            String summary
    ) {
        return """
                다음 내용은 현재 대화보다 이전에 진행된 대화의 요약 메모리입니다.

                [대화 요약 메모리]
                %s

                답변할 때 이 메모리를 이전 대화의 사실과 맥락으로 참고하세요.
                메모리에 없는 사실은 임의로 만들지 마세요.
                """.formatted(
                summary
        );
    }

    private void saveSummary(
            String roomId,
            String summary
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatRoom chatRoom =
                    findRoom(
                            roomId
                    );

            chatRoom.updateSummary(
                    summary
            );
        });
    }

    private ChatRoom findRoom(
            String roomId
    ) {
        return chatRoomRepository.findById(
                        roomId
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "채팅방을 찾을 수 없습니다. roomId=" + roomId
                        )
                );
    }

    private void removeLastAssistantMessage(
            List<ChatMessage> messages
    ) {
        for (
                int index = messages.size() - 1;
                index >= 0;
                index--
        ) {
            ChatMessage message =
                    messages.get(index);

            if (!ASSISTANT_ROLE.equalsIgnoreCase(message.getRole())) {
                continue;
            }

            messages.remove(index);

            return;
        }
    }

    private record SummaryTarget(
            String existingSummary,
            List<ChatMessage> messages
    ) {
    }
}