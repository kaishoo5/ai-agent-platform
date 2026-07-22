import {useEffect, useRef,} from "react";

import {useChatStore} from "../../store/chatStore";

import ChatMessageItem from "./ChatMessageItem";

function ChatMessageList() {
    const messages = useChatStore((state) => state.messages);

    const messageEndRef = useRef<HTMLDivElement | null>(null);

    useEffect(() => {
        messageEndRef.current?.scrollIntoView({
            behavior: "smooth",
        });
    }, [messages]);

    if (messages.length === 0) {
        return (
            <div className="message-list">
                <div className="empty-message">
                    <h3>무엇을 도와드릴까요?</h3>
                    <p>아래 입력창에 질문을 입력하세요.</p>
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
                    />
                ))}

                <div ref={messageEndRef} />
            </div>
        </div>
    );
}

export default ChatMessageList;