import {useEffect, useRef,} from "react";

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

    const activeRoom = rooms.find(
        (room) => room.id === activeRoomId,
    );

    const messages = activeRoom?.messages ?? [];

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

        const abortController =
            new AbortController();

        updateMessageContent(
            activeRoomId,
            assistantMessageId,
            "",
        );

        startGenerating(
            abortController,
        );

        try {
            await streamChat(
                activeRoomId,
                requestMessages,
                (chunk) => {
                    appendMessageContent(
                        activeRoomId,
                        assistantMessageId,
                        chunk,
                    );
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

                return;
            }

            updateMessageContent(
                activeRoomId,
                assistantMessageId,
                previousContent,
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
                <div className="empty-message">
                    <h3>무엇을 도와드릴까요?</h3>

                    <p>
                        아래 입력창에 질문을 입력하세요.
                    </p>
                </div>
            </div>
        );
    }

    return (
        <div className="message-list message-list-active">
            <div className="message-list-inner">
                {messages.map((message) => (
                    <ChatMessageItem
                        key={message.id}
                        message={message}
                        isLastAssistant={
                            message.id
                            === lastAssistantMessage?.id
                        }
                        isGenerating={
                            isGenerating
                        }
                        onRegenerate={() => {
                            void handleRegenerate(
                                message.id,
                            );
                        }}
                    />
                ))}

                <div ref={messageEndRef} />
            </div>
        </div>
    );
}

export default ChatMessageList;