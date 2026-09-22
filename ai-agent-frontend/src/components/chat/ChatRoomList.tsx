import {useState} from "react";
import {useChatStore} from "../../store/chatStore";
import {useNavigate} from "react-router-dom";

interface ChatRoomListProps {
    onRoomSelected?: () => void;
}

function ChatRoomList({
                          onRoomSelected,
                      }: ChatRoomListProps) {
    const navigate = useNavigate();

    const [
        deleteConfirmRoomId,
        setDeleteConfirmRoomId,
    ] = useState<string | null>(
        null,
    );

    const [
        editingRoomId,
        setEditingRoomId,
    ] = useState<string | null>(
        null,
    );

    const [
        editingTitle,
        setEditingTitle,
    ] = useState(
        "",
    );

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

    const updateRoomTitle = useChatStore(
        (state) => state.updateRoomTitle,
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

    const handleEditStart = (
        event: React.MouseEvent<HTMLSpanElement>,
        roomId: string,
        title: string,
    ): void => {
        event.stopPropagation();

        setDeleteConfirmRoomId(
            null,
        );

        setEditingRoomId(
            roomId,
        );

        setEditingTitle(
            title,
        );
    };

    const handleEditCancel = (): void => {
        setEditingRoomId(
            null,
        );

        setEditingTitle(
            "",
        );
    };

    const handleEditSave = async (
        roomId: string,
    ): Promise<void> => {
        const title =
            editingTitle.trim();

        if (!title) {
            handleEditCancel();
            return;
        }

        try {
            await updateRoomTitle(
                roomId,
                title,
            );

            handleEditCancel();
        } catch (error) {
            console.error(
                "채팅방 제목 변경 중 오류가 발생했습니다.",
                error,
            );
        }
    };

    const handleDeleteRequest = (
        event: React.MouseEvent<HTMLButtonElement>,
        roomId: string,
    ): void => {
        event.stopPropagation();

        setDeleteConfirmRoomId(
            roomId,
        );
    };

    const handleDeleteCancel = (
        event: React.MouseEvent<HTMLButtonElement>,
    ): void => {
        event.stopPropagation();

        setDeleteConfirmRoomId(
            null,
        );
    };

    const handleDeleteConfirm = async (
        event: React.MouseEvent<HTMLButtonElement>,
        roomId: string,
    ): Promise<void> => {
        event.stopPropagation();

        try {
            await deleteRoom(
                roomId,
            );

            setDeleteConfirmRoomId(
                null,
            );
        } catch (error) {
            console.error(
                "채팅방 삭제 중 오류가 발생했습니다.",
                error,
            );
        }
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
                            aria-current={
                                isActive
                                    ? "page"
                                    : undefined
                            }
                            title={room.title}
                            onClick={() => {
                                if (editingRoomId === room.id) {
                                    return;
                                }

                                void handleSelectRoom(
                                    room.id,
                                );
                            }}
                            onKeyDown={(event) => {
                                if (
                                    event.key === "Enter"
                                    || event.key === " "
                                ) {
                                    event.preventDefault();

                                    void handleSelectRoom(
                                        room.id,
                                    );
                                }
                            }}
                        >
                            <span className="chat-room-icon">
                                ◇
                            </span>

                            {editingRoomId === room.id ? (
                                <input
                                    className="chat-room-title-input"
                                    value={editingTitle}
                                    maxLength={200}
                                    autoFocus
                                    aria-label="채팅방 제목"
                                    onClick={(event) => {
                                        event.stopPropagation();
                                    }}
                                    onChange={(event) => {
                                        setEditingTitle(
                                            event.target.value,
                                        );
                                    }}
                                    onKeyDown={(event) => {
                                        event.stopPropagation();

                                        if (event.key === "Enter") {
                                            event.preventDefault();

                                            void handleEditSave(
                                                room.id,
                                            );

                                            return;
                                        }

                                        if (event.key === "Escape") {
                                            event.preventDefault();

                                            handleEditCancel();
                                        }
                                    }}
                                    onBlur={() => {
                                        void handleEditSave(
                                            room.id,
                                        );
                                    }}
                                />
                            ) : (
                                <span
                                    className="chat-room-title"
                                    onDoubleClick={(event) => {
                                        handleEditStart(
                                            event,
                                            room.id,
                                            room.title,
                                        );
                                    }}
                                >
                                    {room.title}
                                </span>
                            )}

                            {editingRoomId !== room.id && (
                                <button
                                    type="button"
                                    className="chat-room-delete-button"
                                    aria-label="채팅방 삭제"
                                    aria-expanded={
                                        deleteConfirmRoomId
                                        === room.id
                                    }
                                    onClick={(event) => {
                                        handleDeleteRequest(
                                            event,
                                            room.id,
                                        );
                                    }}
                                >
                                    ×
                                </button>
                            )}

                            {deleteConfirmRoomId === room.id && (
                                <div
                                    className="chat-room-delete-confirm"
                                    role="dialog"
                                    aria-label="채팅방 삭제 확인"
                                    onClick={(event) => {
                                        event.stopPropagation();
                                    }}
                                    onKeyDown={(event) => {
                                        event.stopPropagation();
                                    }}
                                >
                                    <div className="chat-room-delete-confirm-title">
                                        이 채팅을 삭제할까요?
                                    </div>

                                    <div className="chat-room-delete-confirm-description">
                                        삭제한 대화는 복구할 수 없습니다.
                                    </div>

                                    <div className="chat-room-delete-confirm-actions">
                                        <button
                                            type="button"
                                            className="chat-room-delete-cancel-button"
                                            onClick={handleDeleteCancel}
                                        >
                                            취소
                                        </button>

                                        <button
                                            type="button"
                                            className="chat-room-delete-confirm-button"
                                            onClick={(event) => {
                                                void handleDeleteConfirm(
                                                    event,
                                                    room.id,
                                                );
                                            }}
                                        >
                                            삭제
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>
        </section>
    );
}

export default ChatRoomList;