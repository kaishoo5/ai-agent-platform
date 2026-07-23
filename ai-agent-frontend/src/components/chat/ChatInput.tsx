import {type ChangeEvent, type FormEvent, type KeyboardEvent, useEffect, useRef, useState,} from "react";

import {streamChat} from "../../services/chatStreamService";
import {useChatStore} from "../../store/chatStore";
import type {ChatMessage} from "../../types/chat";

function ChatInput() {
    const [input, setInput] = useState("");

    const inputRef =
        useRef<HTMLTextAreaElement | null>(null);

    const refreshRooms = useChatStore(
        (state) => state.refreshRooms,
    );

    const activeRoomId = useChatStore(
        (state) => state.activeRoomId,
    );

    const createRoom = useChatStore(
        (state) => state.createRoom,
    );

    const addMessage = useChatStore(
        (state) => state.addMessage,
    );

    const appendMessageContent = useChatStore(
        (state) => state.appendMessageContent,
    );

    const updateMessageContent = useChatStore(
        (state) => state.updateMessageContent,
    );

    const setActiveRoom = useChatStore(
        (state) => state.setActiveRoom,
    );

    const isGenerating = useChatStore(
        (state) => state.isGenerating,
    );

    const startGenerating = useChatStore(
        (state) => state.startGenerating,
    );

    const finishGenerating = useChatStore(
        (state) => state.finishGenerating,
    );

    const stopGenerating = useChatStore(
        (state) => state.stopGenerating,
    );

    useEffect(() => {
        if (isGenerating) {
            return;
        }

        requestAnimationFrame(() => {
            inputRef.current?.focus();
        });
    }, [
        isGenerating,
        activeRoomId,
    ]);

    const handleChange = (
        event: ChangeEvent<HTMLTextAreaElement>,
    ) => {
        setInput(event.target.value);
    };

    const sendMessage = async (): Promise<void> => {
        const trimmedInput = input.trim();

        if (
            !trimmedInput
            || isGenerating
        ) {
            return;
        }

        let targetRoomId = activeRoomId;

        if (!targetRoomId) {
            targetRoomId = await createRoom();
        }

        const targetRoom = useChatStore
            .getState()
            .rooms
            .find(
                (room) =>
                    room.id === targetRoomId,
            );

        const currentMessages =
            targetRoom?.messages
            ?? [];

        const userMessage: ChatMessage = {
            id: crypto.randomUUID(),
            roomId: targetRoomId,
            role: "USER",
            content: trimmedInput,
            createdAt: new Date().toISOString(),
        };

        const assistantMessage: ChatMessage = {
            id: crypto.randomUUID(),
            roomId: targetRoomId,
            role: "ASSISTANT",
            content: "",
            createdAt: new Date().toISOString(),
        };

        const requestMessages: ChatMessage[] = [
            ...currentMessages,
            userMessage,
        ];

        addMessage(
            targetRoomId,
            userMessage,
        );

        addMessage(
            targetRoomId,
            assistantMessage,
        );

        setInput("");

        const abortController =
            new AbortController();

        startGenerating(
            abortController,
        );

        try {
            await streamChat(
                targetRoomId,
                requestMessages,
                (chunk) => {
                    appendMessageContent(
                        targetRoomId,
                        assistantMessage.id,
                        chunk,
                    );
                },
                abortController.signal,
            );

            await refreshRooms();

            await setActiveRoom(
                targetRoomId,
            );
        } catch (error) {
            if (
                error instanceof DOMException
                && error.name === "AbortError"
            ) {
                console.log(
                    "사용자가 AI 응답 생성을 중지했습니다.",
                );

                const currentRoom = useChatStore
                    .getState()
                    .rooms
                    .find(
                        (room) =>
                            room.id === targetRoomId,
                    );

                const currentAssistantMessage =
                    currentRoom?.messages.find(
                        (message) =>
                            message.id
                            === assistantMessage.id,
                    );

                if (
                    !currentAssistantMessage
                    || currentAssistantMessage
                        .content
                        .length === 0
                ) {
                    updateMessageContent(
                        targetRoomId,
                        assistantMessage.id,
                        "응답이 중단되었습니다.",
                    );
                }

                return;
            }

            console.error(
                "AI 응답 생성 중 오류가 발생했습니다.",
                error,
            );

            updateMessageContent(
                targetRoomId,
                assistantMessage.id,
                "응답 생성 중 오류가 발생했습니다.",
            );
        } finally {
            finishGenerating();
        }
    };

    const handleStop = (): void => {
        stopGenerating();
    };

    const handleSubmit = (
        event: FormEvent<HTMLFormElement>,
    ) => {
        event.preventDefault();

        void sendMessage();
    };

    const handleKeyDown = (
        event: KeyboardEvent<HTMLTextAreaElement>,
    ) => {
        if (
            event.key === "Enter"
            && !event.shiftKey
        ) {
            event.preventDefault();

            void sendMessage();
        }
    };

    return (
        <form
            className="chat-input-area"
            onSubmit={handleSubmit}
        >
            <textarea
                ref={inputRef}
                className="chat-input"
                value={input}
                placeholder={
                    isGenerating
                        ? "AI가 응답 중입니다."
                        : "메시지를 입력하세요."
                }
                rows={1}
                disabled={isGenerating}
                onChange={handleChange}
                onKeyDown={handleKeyDown}
            />

            {
                isGenerating
                    ? (
                        <button
                            type="button"
                            className="stop-button"
                            onClick={handleStop}
                        >
                            <span className="stop-button-icon" />

                            중지
                        </button>
                    )
                    : (
                        <button
                            type="submit"
                            className="send-button"
                            disabled={!input.trim()}
                        >
                            전송
                        </button>
                    )
            }
        </form>
    );
}

export default ChatInput;