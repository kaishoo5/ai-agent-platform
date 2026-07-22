import {type ChangeEvent, type FormEvent, type KeyboardEvent, useState,} from "react";

import chatService from "../../services/chatService";
import {useChatStore} from "../../store/chatStore";

function ChatInput() {
    const [input, setInput] = useState("");
    const [isSending, setIsSending] = useState(false);

    const addMessage = useChatStore((state) => state.addMessage);

    const handleChange = (event: ChangeEvent<HTMLTextAreaElement>) => {
        setInput(event.target.value);
    };

    const sendMessage = async () => {
        const trimmedInput = input.trim();

        if (!trimmedInput || isSending) {
            return;
        }

        addMessage({
            id: crypto.randomUUID(),
            role: "USER",
            content: trimmedInput,
            createdAt: new Date().toISOString(),
        });

        setInput("");
        setIsSending(true);

        try {
            const response = await chatService.sendMessage({
                message: trimmedInput,
            });

            addMessage({
                id: crypto.randomUUID(),
                role: "ASSISTANT",
                content: response.message,
                createdAt: new Date().toISOString(),
            });
        } catch (error) {
            console.error("채팅 API 호출 중 오류가 발생했습니다.", error);

            addMessage({
                id: crypto.randomUUID(),
                role: "ASSISTANT",
                content: "메시지 처리 중 오류가 발생했습니다.",
                createdAt: new Date().toISOString(),
            });
        } finally {
            setIsSending(false);
        }
    };

    const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        void sendMessage();
    };

    const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
        if (event.key === "Enter" && !event.shiftKey) {
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
                {isSending ? "전송 중" : "전송"}
            </button>
        </form>
    );
}

export default ChatInput;