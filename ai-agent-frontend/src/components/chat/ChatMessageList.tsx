import {useEffect, useMemo, useRef, useState} from "react";

import {editChatMessage} from "../../api/chatApi";
import {streamChat} from "../../services/chatStreamService";
import {useChatStore} from "../../store/chatStore";
import type {ChatMessage} from "../../types/chat";

import ChatMessageItem from "./ChatMessageItem";

const PROMPT_SUGGESTIONS = [
    {
        icon: "⌕",
        title: "웹에서 찾아보기",
        description: "최신 정보를 검색해서 알려줘",
        prompt: "오늘 주요 AI 뉴스를 찾아서 정리해줘",
    },
    {
        icon: "▤",
        title: "파일 분석",
        description: "문서의 내용을 분석하고 요약",
        prompt: "첨부한 파일의 핵심 내용을 분석해서 정리해줘",
    },
    {
        icon: "▶",
        title: "영상 분석",
        description: "영상 내용과 주요 장면 분석",
        prompt: "첨부한 영상의 내용을 분석하고 핵심 내용을 정리해줘",
    },
    {
        icon: "</>",
        title: "코드 도움",
        description: "코드 작성과 개발 문제 해결",
        prompt: "Java로 예제 코드를 작성하고 자세히 설명해줘",
    },
];

function createMessageId(): string {
    return crypto.randomUUID();
}

function ChatMessageList() {
    const rooms = useChatStore(
        (state) => state.rooms,
    );

    const activeRoomId = useChatStore(
        (state) => state.activeRoomId,
    );

    const isGenerating = useChatStore(
        (state) => state.isGenerating,
    );

    const generatingRoomId = useChatStore(
        (state) => state.generatingRoomId,
    );

    const isActiveRoomGenerating =
        isGenerating
        && generatingRoomId === activeRoomId;

    const appendMessageContent = useChatStore(
        (state) => state.appendMessageContent,
    );

    const updateMessageContent = useChatStore(
        (state) => state.updateMessageContent,
    );

    const replaceMessages = useChatStore(
        (state) => state.replaceMessages,
    );

    const startGenerating = useChatStore(
        (state) => state.startGenerating,
    );

    const finishGenerating = useChatStore(
        (state) => state.finishGenerating,
    );

    const refreshRooms = useChatStore(
        (state) => state.refreshRooms,
    );

    const setActiveRoom = useChatStore(
        (state) => state.setActiveRoom,
    );

    const updateMessageVideoResult = useChatStore(
        (state) => state.updateMessageVideoResult,
    );

    const updateMessageSources = useChatStore(
        (state) => state.updateMessageSources,
    );

    const messageExecutionSteps = useChatStore(
        (state) => state.messageExecutionSteps,
    );

    const updateMessageExecutionStep = useChatStore(
        (state) => state.updateMessageExecutionStep,
    );

    const clearMessageExecutionSteps = useChatStore(
        (state) => state.clearMessageExecutionSteps,
    );

    const activeRoom = rooms.find(
        (room) => room.id === activeRoomId,
    );

    const messages = useMemo(
        () =>
            activeRoom?.messages ?? [],
        [
            activeRoom?.messages,
        ],
    );

    const messageEndRef =
        useRef<HTMLDivElement | null>(null);

    const messageListRef =
        useRef<HTMLDivElement | null>(null);

    const [isNearBottom, setIsNearBottom] =
        useState(true);

    const previousRoomIdRef =
        useRef<string | null>(null);

    const lastAssistantMessage = [...messages]
        .reverse()
        .find(
            (message) =>
                message.role === "ASSISTANT",
        );

    useEffect(() => {
        if (!activeRoomId) {
            return;
        }

        const roomChanged =
            previousRoomIdRef.current
            !== activeRoomId;

        previousRoomIdRef.current =
            activeRoomId;

        if (
            roomChanged
            || isNearBottom
        ) {
            messageEndRef.current?.scrollIntoView({
                behavior: roomChanged
                    ? "auto"
                    : "smooth",
            });
        }
    }, [
        activeRoomId,
        messages,
        isNearBottom,
    ]);

    const updateScrollPosition = (): void => {
        const messageList =
            messageListRef.current;

        const messageEnd =
            messageEndRef.current;

        if (
            !messageList
            || !messageEnd
        ) {
            setIsNearBottom(true);

            return;
        }

        const messageListRect =
            messageList.getBoundingClientRect();

        const messageEndRect =
            messageEnd.getBoundingClientRect();

        const distanceFromViewportBottom =
            messageEndRect.bottom
            - messageListRect.bottom;

        setIsNearBottom(
            distanceFromViewportBottom <= 120,
        );
    };

    const handleScroll = (): void => {
        updateScrollPosition();
    };

    useEffect(() => {
        const animationFrameId =
            window.requestAnimationFrame(
                updateScrollPosition,
            );

        return () => {
            window.cancelAnimationFrame(
                animationFrameId,
            );
        };
    }, [
        activeRoomId,
        messages,
    ]);

    const handleScrollToBottom = (): void => {
        messageEndRef.current?.scrollIntoView({
            behavior: "smooth",
        });
    };

    const handlePromptSuggestion = (
        prompt: string,
    ): void => {
        window.dispatchEvent(
            new CustomEvent(
                "chat:prompt-suggestion",
                {
                    detail: prompt,
                },
            ),
        );
    };

    const handleRegenerate = async (
        assistantMessageId: string,
    ): Promise<void> => {
        if (
            !activeRoomId
            || isGenerating
        ) {
            return;
        }

        const currentRoom = useChatStore
            .getState()
            .rooms
            .find(
                (room) =>
                    room.id === activeRoomId,
            );

        if (!currentRoom) {
            return;
        }

        const assistantMessageIndex =
            currentRoom.messages.findIndex(
                (message) =>
                    message.id === assistantMessageId,
            );

        if (assistantMessageIndex < 0) {
            return;
        }

        const assistantMessage =
            currentRoom.messages[
                assistantMessageIndex
                ];

        if (
            assistantMessage.role !== "ASSISTANT"
        ) {
            return;
        }

        const requestMessages =
            currentRoom.messages.slice(
                0,
                assistantMessageIndex,
            );

        const lastRequestMessage =
            requestMessages[
            requestMessages.length - 1
                ];

        if (
            !lastRequestMessage
            || lastRequestMessage.role !== "USER"
        ) {
            console.error(
                "재생성할 사용자 질문을 찾을 수 없습니다.",
            );

            return;
        }

        const previousContent =
            assistantMessage.content;

        const previousVideoResult =
            assistantMessage.videoResult;

        const previousSources = [
            ...assistantMessage.sources,
        ];

        const abortController =
            new AbortController();

        updateMessageContent(
            activeRoomId,
            assistantMessageId,
            "",
        );

        updateMessageVideoResult(
            activeRoomId,
            assistantMessageId,
            [],
        );

        updateMessageSources(
            activeRoomId,
            assistantMessageId,
            [],
        );

        clearMessageExecutionSteps(
            assistantMessageId,
        );

        startGenerating(
            activeRoomId,
            abortController,
        );

        try {
            await streamChat(
                activeRoomId,
                requestMessages,
                {
                    onChunk: (chunk) => {
                        clearMessageExecutionSteps(
                            assistantMessageId,
                        );

                        appendMessageContent(
                            activeRoomId,
                            assistantMessageId,
                            chunk,
                        );
                    },

                    onVideoResult: (videoResult) => {
                        updateMessageVideoResult(
                            activeRoomId,
                            assistantMessageId,
                            videoResult,
                        );
                    },

                    onSources: (sources) => {
                        updateMessageSources(
                            activeRoomId,
                            assistantMessageId,
                            sources,
                        );
                    },

                    onAgentStep: (step) => {
                        updateMessageExecutionStep(
                            assistantMessageId,
                            {
                                id: `agent:${step.code}`,
                                code: step.code,
                                status: step.status,
                                message: step.message,
                            },
                        );
                    },
                },
                abortController.signal,
                true,
            );

            await refreshRooms();

            await setActiveRoom(
                activeRoomId,
            );
        } catch (error) {
            if (
                error instanceof DOMException
                && error.name === "AbortError"
            ) {
                console.log(
                    "사용자가 AI 응답 재생성을 중지했습니다.",
                );

                updateMessageContent(
                    activeRoomId,
                    assistantMessageId,
                    previousContent,
                );

                updateMessageVideoResult(
                    activeRoomId,
                    assistantMessageId,
                    previousVideoResult,
                );

                updateMessageSources(
                    activeRoomId,
                    assistantMessageId,
                    previousSources,
                );

                clearMessageExecutionSteps(
                    assistantMessageId,
                );

                return;
            }

            updateMessageContent(
                activeRoomId,
                assistantMessageId,
                previousContent,
            );

            updateMessageVideoResult(
                activeRoomId,
                assistantMessageId,
                previousVideoResult,
            );

            updateMessageSources(
                activeRoomId,
                assistantMessageId,
                previousSources,
            );

            console.error(
                "AI 응답 재생성 중 오류가 발생했습니다.",
                error,
            );
        } finally {
            finishGenerating();
        }
    };

    const handleEdit = async (
        userMessageId: string,
        content: string,
    ): Promise<void> => {
        if (
            !activeRoomId
            || isGenerating
        ) {
            return;
        }

        const targetRoomId =
            activeRoomId;

        try {
            const editedMessages =
                await editChatMessage(
                    targetRoomId,
                    userMessageId,
                    content,
                );

            const editedUserMessage =
                editedMessages[
                editedMessages.length - 1
                    ];

            if (
                !editedUserMessage
                || editedUserMessage.role !== "USER"
            ) {
                throw new Error(
                    "수정된 사용자 메시지를 찾을 수 없습니다.",
                );
            }

            const assistantMessage: ChatMessage = {
                id: createMessageId(),
                roomId: targetRoomId,
                role: "ASSISTANT",
                content: "",
                videoResult: [],
                sources: [],
                createdAt: new Date().toISOString(),
            };

            replaceMessages(
                targetRoomId,
                [
                    ...editedMessages,
                    assistantMessage,
                ],
            );

            const abortController =
                new AbortController();

            startGenerating(
                targetRoomId,
                abortController,
            );

            await streamChat(
                targetRoomId,
                editedMessages,
                {
                    onChunk: (chunk) => {
                        clearMessageExecutionSteps(
                            assistantMessage.id,
                        );

                        appendMessageContent(
                            targetRoomId,
                            assistantMessage.id,
                            chunk,
                        );
                    },

                    onVideoResult: (videoResult) => {
                        updateMessageVideoResult(
                            targetRoomId,
                            assistantMessage.id,
                            videoResult,
                        );
                    },

                    onSources: (sources) => {
                        updateMessageSources(
                            targetRoomId,
                            assistantMessage.id,
                            sources,
                        );
                    },

                    onAgentStep: (step) => {
                        updateMessageExecutionStep(
                            assistantMessage.id,
                            {
                                id: `agent:${step.code}`,
                                code: step.code,
                                status: step.status,
                                message: step.message,
                            },
                        );
                    },
                },
                abortController.signal,
                true,
            );

            await refreshRooms();

            await setActiveRoom(
                targetRoomId,
            );
        } catch (error) {
            console.error(
                "사용자 메시지 수정 후 재생성 중 오류가 발생했습니다.",
                error,
            );

            await setActiveRoom(
                targetRoomId,
            );

            throw error;
        } finally {
            finishGenerating();
        }
    };

    if (messages.length === 0) {
        return (
            <div className="message-list">
                <div className="empty-chat">
                    <div className="empty-chat-content">
                        <div className="empty-chat-icon">
                            A
                        </div>

                        <h2 className="empty-chat-title">
                            무엇을 도와드릴까요?
                        </h2>

                        <p className="empty-chat-description">
                            파일 분석, 코드 수정, 영상 분석부터
                            <br />
                            일반적인 질문까지 AI Agent에게 요청해보세요.
                        </p>

                        <div className="empty-chat-suggestions">
                            {PROMPT_SUGGESTIONS.map(
                                (suggestion) => (
                                    <button
                                        key={suggestion.title}
                                        type="button"
                                        className="empty-chat-suggestion"
                                        onClick={() => {
                                            handlePromptSuggestion(
                                                suggestion.prompt,
                                            );
                                        }}
                                    >
                                        <span className="empty-chat-suggestion-icon">
                                            {suggestion.icon}
                                        </span>

                                        <span className="empty-chat-suggestion-content">
                                            <strong>
                                                {suggestion.title}
                                            </strong>

                                            <span>
                                                {suggestion.description}
                                            </span>
                                        </span>
                                    </button>
                                ),
                            )}
                        </div>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div
            ref={messageListRef}
            className="message-list message-list-active"
            onScroll={handleScroll}
        >
            <div className="message-list-inner">
                <div className="conversation-stream">
                    {messages.map((message) => (
                        <ChatMessageItem
                            key={message.id}
                            message={message}
                            isLastAssistant={
                                message.id
                                === lastAssistantMessage?.id
                            }
                            isGenerating={
                                isActiveRoomGenerating
                            }
                            executionSteps={
                                messageExecutionSteps[
                                    message.id
                                    ] ?? []
                            }
                            onRegenerate={() => {
                                void handleRegenerate(
                                    message.id,
                                );
                            }}
                            onEdit={(content) =>
                                handleEdit(
                                    message.id,
                                    content,
                                )
                            }
                        />
                    ))}

                    <div
                        ref={messageEndRef}
                        className="message-list-end"
                    />
                </div>
            </div>

            {!isNearBottom && (
                <button
                    type="button"
                    className="scroll-to-bottom-button"
                    aria-label="최신 메시지로 이동"
                    title="최신 메시지로 이동"
                    onClick={handleScrollToBottom}
                >
                    <span aria-hidden="true">
                        ↓
                    </span>

                    <span>
                        최신 메시지
                    </span>
                </button>
            )}
        </div>
    );
}

export default ChatMessageList;
