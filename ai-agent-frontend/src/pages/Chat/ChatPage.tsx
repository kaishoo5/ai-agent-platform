import {useEffect} from "react";

import ChatInput from "../../components/chat/ChatInput";
import ChatMessageList from "../../components/chat/ChatMessageList";
import {useChatStore} from "../../store/chatStore";

function ChatPage() {
    const loadRooms = useChatStore(
        (state) => state.loadRooms,
    );

    const rooms = useChatStore(
        (state) => state.rooms,
    );

    const activeRoomId = useChatStore(
        (state) => state.activeRoomId,
    );

    const isGenerating = useChatStore(
        (state) => state.isGenerating,
    );

    const activeRoom = rooms.find(
        (room) => room.id === activeRoomId,
    );

    useEffect(() => {
        void loadRooms();
    }, [
        loadRooms,
    ]);

    return (
        <div className="chat-page">
            <header className="chat-header">
                <div className="chat-header-title">
                    <strong>
                        {activeRoom?.title || "New conversation"}
                    </strong>

                    <span>
                        AI Agent Workspace
                    </span>
                </div>

                <div className="chat-header-status">
                    <span
                        className={
                            isGenerating
                                ? "chat-header-status-dot generating"
                                : "chat-header-status-dot"
                        }
                    />

                    <span>
                        {isGenerating
                            ? "Generating"
                            : "Ready"}
                    </span>
                </div>
            </header>

            <ChatMessageList />

            <ChatInput />
        </div>
    );
}

export default ChatPage;