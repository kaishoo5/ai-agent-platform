import {useEffect} from "react";

import ChatInput from "../../components/chat/ChatInput";
import ChatMessageList from "../../components/chat/ChatMessageList";
import {useChatStore} from "../../store/chatStore";

function ChatPage() {
    const loadRooms = useChatStore(
        (state) => state.loadRooms,
    );

    useEffect(() => {
        void loadRooms();
    }, [
        loadRooms,
    ]);

    return (
        <div className="chat-page">
            <ChatMessageList />
            <ChatInput />
        </div>
    );
}

export default ChatPage;