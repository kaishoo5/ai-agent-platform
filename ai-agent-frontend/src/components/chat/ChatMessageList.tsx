import {useEffect, useMemo, useRef} from "react";

import {streamChat} from "../../services/chatStreamService";
import {useChatStore} from "../../store/chatStore";

import ChatMessageItem from "./ChatMessageItem";

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

    const lastAssistantMessage = [...messages]
        .reverse()
        .find(
            (message) =>
                message.role === "ASSISTANT",
        );

    useEffect(() => {
        messageEndRef.current?.scrollIntoView({
            behavior: "smooth",
        });
    }, [messages]);

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

            console.error(
                "AI 응답 재생성 중 오류가 발생했습니다.",
                error,
            );
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
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="message-list message-list-active">
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
                        />
                    ))}

                    <div
                        ref={messageEndRef}
                        className="message-list-end"
                    />
                </div>
            </div>
        </div>
    );
}

export default ChatMessageList;