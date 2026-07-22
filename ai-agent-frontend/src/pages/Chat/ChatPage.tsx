import ChatInput from "../../components/chat/ChatInput";
import ChatMessageList from "../../components/chat/ChatMessageList";

function ChatPage() {
    return (
        <section className="page">
            <header className="page-header">
                <div>
                    <h2>새 대화</h2>
                    <p>AI Agent에게 궁금한 내용을 질문해보세요.</p>
                </div>
            </header>

            <div className="chat-container">
                <ChatMessageList />
                <ChatInput />
            </div>
        </section>
    );
}

export default ChatPage;