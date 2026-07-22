import {type ChangeEvent, type FormEvent, type KeyboardEvent, useState,} from "react";

import chatStreamService from "../../services/chatStreamService";
import {useChatStore} from "../../store/chatStore";

function ChatInput() {
    const [input, setInput] = useState("");
    const [isSending, setIsSending] = useState(false);

    const addMessage = useChatStore(
        (state) => state.addMessage,
    );

    const appendMessageContent = useChatStore(
        (state) => state.appendMessageContent,
    );

    const updateMessageContent = useChatStore(
        (state) => state.updateMessageContent,
    );

    const handleChange = (
        event: ChangeEvent<HTMLTextAreaElement>,
    ) => {
        setInput(event.target.value);
    };

    const sendMessage = async () => {
        const trimmedInput = input.trim();

        if (!trimmedInput || isSending) {
            return;
        }

        const assistantMessageId = crypto.randomUUID();

        addMessage({
            id: crypto.randomUUID(),
            role: "USER",
            content: trimmedInput,
            createdAt: new Date().toISOString(),
        });

        addMessage({
            id: assistantMessageId,
            role: "ASSISTANT",
            content: "",
            createdAt: new Date().toISOString(),
        });

        setInput("");
        setIsSending(true);

        try {
            await chatStreamService.sendMessage(
                {
                    message: trimmedInput,
                },
                (chunk) => {
                    appendMessageContent(
                        assistantMessageId,
                        chunk,
                    );
                },
            );
        } catch (error) {
            console.error(
                "채팅 스트리밍 중 오류가 발생했습니다.",
                error,
            );

            updateMessageContent(
                assistantMessageId,
                "메시지 처리 중 오류가 발생했습니다.",
            );
        } finally {
            setIsSending(false);
        }
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
                className="chat-input"
                value={input}
                placeholder={
                    isSending
                        ? "AI가 응답 중입니다."
                        : "메시지를 입력하세요."
                }
                rows={1}
                disabled={isSending}
                onChange={handleChange}
                onKeyDown={handleKeyDown}
            />

            <button
                type="submit"
                className="send-button"
                disabled={!input.trim() || isSending}
            >
                {isSending ? "응답 중" : "전송"}
            </button>
        </form>
    );
}

export default ChatInput;