import {type ChangeEvent, type FormEvent, type KeyboardEvent, useState,} from "react";

import {useChatStore} from "../../store/chatStore";

function ChatInput() {
    const [input, setInput] = useState("");

    const addMessage = useChatStore((state) => state.addMessage);

    const handleChange = (event: ChangeEvent<HTMLTextAreaElement>) => {
        setInput(event.target.value);
    };

    const sendMessage = () => {
        const trimmedInput = input.trim();

        if (!trimmedInput) {
            return;
        }

        addMessage({
            id: crypto.randomUUID(),
            role: "USER",
            content: trimmedInput,
            createdAt: new Date().toISOString(),
        });

        setInput("");

        window.setTimeout(() => {
            addMessage({
                id: crypto.randomUUID(),
                role: "ASSISTANT",
                content: `"${trimmedInput}" 메시지를 정상적으로 받았습니다.`,
                createdAt: new Date().toISOString(),
            });
        }, 500);
    };

    const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        sendMessage();
    };

    const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            sendMessage();
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
                placeholder="메시지를 입력하세요."
                rows={1}
                onChange={handleChange}
                onKeyDown={handleKeyDown}
            />

            <button
                type="submit"
                className="send-button"
                disabled={!input.trim()}
            >
                전송
            </button>
        </form>
    );
}

export default ChatInput;