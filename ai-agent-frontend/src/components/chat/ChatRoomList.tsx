import {useChatStore} from "../../store/chatStore";
import {useNavigate} from "react-router-dom";

interface ChatRoomListProps {
    onRoomSelected?: () => void;
}

function ChatRoomList({
                          onRoomSelected,
                      }: ChatRoomListProps) {
    const navigate = useNavigate();

    const rooms = useChatStore(
        (state) => state.rooms,
    );

    const activeRoomId = useChatStore(
        (state) => state.activeRoomId,
    );

    const createRoom = useChatStore(
        (state) => state.createRoom,
    );

    const setActiveRoom = useChatStore(
        (state) => state.setActiveRoom,
    );

    const deleteRoom = useChatStore(
        (state) => state.deleteRoom,
    );

    const handleCreateRoom = async (): Promise<void> => {
        try {
            await createRoom();

            navigate(
                "/",
            );

            onRoomSelected?.();
        } catch (error) {
            console.error(
                "채팅방 생성 중 오류가 발생했습니다.",
                error,
            );
        }
    };

    const handleSelectRoom = async (
        roomId: string,
    ): Promise<void> => {
        await setActiveRoom(
            roomId,
        );

        navigate(
            "/",
        );

        onRoomSelected?.();
    };

    const handleDeleteRoom = async (
        event: React.MouseEvent<HTMLButtonElement>,
        roomId: string,
    ): Promise<void> => {
        event.stopPropagation();

        await deleteRoom(
            roomId,
        );
    };

    return (
        <section className="chat-room-section">
            <button
                type="button"
                className="new-chat-button"
                onClick={() => {
                    void handleCreateRoom();
                }}
            >
                <span className="new-chat-button-icon">
                    +
                </span>

                <span>
                    New chat
                </span>
            </button>

            <div className="chat-room-header">
                Recent
            </div>

            <div className="chat-room-list">
                {rooms.map((room) => {
                    const isActive =
                        room.id === activeRoomId;

                    return (
                        <div
                            key={room.id}
                            className={
                                isActive
                                    ? "chat-room-item active"
                                    : "chat-room-item"
                            }
                            role="button"
                            tabIndex={0}
                            onClick={() => {
                                void handleSelectRoom(
                                    room.id,
                                );
                            }}
                            onKeyDown={(event) => {
                                if (
                                    event.key === "Enter"
                                    || event.key === " "
                                ) {
                                    void handleSelectRoom(
                                        room.id,
                                    );
                                }
                            }}
                        >
                            <span className="chat-room-icon">
                                ◇
                            </span>

                            <span className="chat-room-title">
                                {room.title}
                            </span>

                            <button
                                type="button"
                                className="chat-room-delete-button"
                                aria-label="채팅방 삭제"
                                onClick={(event) => {
                                    void handleDeleteRoom(
                                        event,
                                        room.id,
                                    );
                                }}
                            >
                                ×
                            </button>
                        </div>
                    );
                })}
            </div>
        </section>
    );
}

export default ChatRoomList;