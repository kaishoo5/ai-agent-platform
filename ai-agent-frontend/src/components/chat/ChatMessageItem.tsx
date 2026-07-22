import type {ChatMessage} from "../../types/chat";

interface ChatMessageItemProps {
    message: ChatMessage;
}

function ChatMessageItem({
                             message,
                         }: ChatMessageItemProps) {
    const isUser = message.role === "USER";

    return (
        <article
            className={
                isUser
                    ? "chat-message chat-message-user"
                    : "chat-message chat-message-assistant"
            }
        >
            <div className="message-avatar">
                {isUser ? "나" : "AI"}
            </div>

            <div className="message-content">
                <div className="message-role">
                    {isUser ? "사용자" : "AI Agent"}
                </div>

                <div className="message-text">
                    {message.content}
                </div>
            </div>
        </article>
    );
}

export default ChatMessageItem;