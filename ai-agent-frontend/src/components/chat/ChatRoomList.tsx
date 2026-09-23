import {useEffect, useState} from "react";
import {useNavigate} from "react-router-dom";
import {useChatStore} from "../../store/chatStore";
import type {ChatRoom} from "../../types/chat";

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
    ] = useState<string | null>(null);

    const [
        menuRoomId,
        setMenuRoomId,
    ] = useState<string | null>(null);

    const [
        editingRoomId,
        setEditingRoomId,
    ] = useState<string | null>(null);

    const [
        editingTitle,
        setEditingTitle,
    ] = useState("");

    const [
        searchQuery,
        setSearchQuery,
    ] = useState("");

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

    const updateRoomPinned = useChatStore(
        (state) => state.updateRoomPinned,
    );

    useEffect(() => {
        if (!menuRoomId) {
            return;
        }

        const handleDocumentPointerDown = (
            event: MouseEvent,
        ): void => {
            const target = event.target;

            if (!(target instanceof Element)) {
                return;
            }

            if (
                target.closest(
                    `[data-room-menu="${menuRoomId}"]`,
                )
            ) {
                return;
            }

            setMenuRoomId(null);
        };

        document.addEventListener(
            "mousedown",
            handleDocumentPointerDown,
        );

        return () => {
            document.removeEventListener(
                "mousedown",
                handleDocumentPointerDown,
            );
        };
    }, [menuRoomId]);

    const handleCreateRoom = async (): Promise<void> => {
        try {
            await createRoom();
            navigate("/");
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
        await setActiveRoom(roomId);
        navigate("/");
        onRoomSelected?.();
    };

    const handleEditStart = (
        roomId: string,
        title: string,
    ): void => {
        setMenuRoomId(null);
        setDeleteConfirmRoomId(null);
        setEditingRoomId(roomId);
        setEditingTitle(title);
    };

    const handleEditCancel = (): void => {
        setEditingRoomId(null);
        setEditingTitle("");
    };

    const handleEditSave = async (
        roomId: string,
    ): Promise<void> => {
        const title = editingTitle.trim();

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

    const handlePinnedToggle = async (
        room: ChatRoom,
    ): Promise<void> => {
        setMenuRoomId(null);

        try {
            await updateRoomPinned(
                room.id,
                !room.pinned,
            );
        } catch (error) {
            console.error(
                "채팅방 고정 상태 변경 중 오류가 발생했습니다.",
                error,
            );
        }
    };

    const handleDeleteRequest = (
        roomId: string,
    ): void => {
        setMenuRoomId(null);
        setDeleteConfirmRoomId(roomId);
    };

    const handleDeleteCancel = (
        event: React.MouseEvent<HTMLButtonElement>,
    ): void => {
        event.stopPropagation();
        setDeleteConfirmRoomId(null);
    };

    const handleDeleteConfirm = async (
        event: React.MouseEvent<HTMLButtonElement>,
        roomId: string,
    ): Promise<void> => {
        event.stopPropagation();

        try {
            await deleteRoom(roomId);
            setDeleteConfirmRoomId(null);
        } catch (error) {
            console.error(
                "채팅방 삭제 중 오류가 발생했습니다.",
                error,
            );
        }
    };

    const normalizedSearchQuery =
        searchQuery.trim().toLowerCase();

    const filteredRooms = normalizedSearchQuery
        ? rooms.filter((room) =>
            room.title
                .toLowerCase()
                .includes(normalizedSearchQuery)
        )
        : rooms;

    const pinnedRooms = filteredRooms.filter(
        (room) => room.pinned,
    );

    const recentRooms = filteredRooms.filter(
        (room) => !room.pinned,
    );

    const renderRoom = (
        room: ChatRoom,
    ) => {
        const isActive =
            room.id === activeRoomId;

        const isMenuOpen =
            menuRoomId === room.id;

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
                    if (
                        editingRoomId === room.id
                        || deleteConfirmRoomId === room.id
                    ) {
                        return;
                    }

                    void handleSelectRoom(room.id);
                }}
                onKeyDown={(event) => {
                    if (
                        editingRoomId === room.id
                        || deleteConfirmRoomId === room.id
                    ) {
                        return;
                    }

                    if (
                        event.key === "Enter"
                        || event.key === " "
                    ) {
                        event.preventDefault();
                        void handleSelectRoom(room.id);
                    }
                }}
            >
                <span
                    className={
                        room.pinned
                            ? "chat-room-icon pinned"
                            : "chat-room-icon"
                    }
                    aria-hidden="true"
                >
                    {room.pinned ? "◆" : "◇"}
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
                                void handleEditSave(room.id);
                                return;
                            }

                            if (event.key === "Escape") {
                                event.preventDefault();
                                handleEditCancel();
                            }
                        }}
                        onBlur={() => {
                            void handleEditSave(room.id);
                        }}
                    />
                ) : (
                    <span
                        className="chat-room-title"
                        onDoubleClick={(event) => {
                            event.stopPropagation();
                            handleEditStart(
                                room.id,
                                room.title,
                            );
                        }}
                    >
                        {room.title}
                    </span>
                )}

                {editingRoomId !== room.id && (
                    <div
                        className="chat-room-menu-wrap"
                        data-room-menu={room.id}
                    >
                        <button
                            type="button"
                            className="chat-room-menu-button"
                            aria-label="채팅방 메뉴"
                            aria-expanded={isMenuOpen}
                            title="채팅방 메뉴"
                            onClick={(event) => {
                                event.stopPropagation();
                                setDeleteConfirmRoomId(null);
                                setMenuRoomId(
                                    isMenuOpen
                                        ? null
                                        : room.id,
                                );
                            }}
                        >
                            ⋯
                        </button>

                        {isMenuOpen && (
                            <div
                                className="chat-room-menu"
                                role="menu"
                                onClick={(event) => {
                                    event.stopPropagation();
                                }}
                            >
                                <button
                                    type="button"
                                    role="menuitem"
                                    onClick={() => {
                                        void handlePinnedToggle(room);
                                    }}
                                >
                                    <span aria-hidden="true">
                                        {room.pinned ? "◇" : "◆"}
                                    </span>
                                    <span>
                                        {room.pinned
                                            ? "고정 해제"
                                            : "고정"}
                                    </span>
                                </button>

                                <button
                                    type="button"
                                    role="menuitem"
                                    onClick={() => {
                                        handleEditStart(
                                            room.id,
                                            room.title,
                                        );
                                    }}
                                >
                                    <span aria-hidden="true">✎</span>
                                    <span>이름 변경</span>
                                </button>

                                <div className="chat-room-menu-divider" />

                                <button
                                    type="button"
                                    role="menuitem"
                                    className="danger"
                                    onClick={() => {
                                        handleDeleteRequest(room.id);
                                    }}
                                >
                                    <span aria-hidden="true">×</span>
                                    <span>삭제</span>
                                </button>
                            </div>
                        )}
                    </div>
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
                <span className="new-chat-button-icon">+</span>
                <span>New chat</span>
            </button>

            <div className="chat-room-search">
                <span
                    className="chat-room-search-icon"
                    aria-hidden="true"
                >
                    ⌕
                </span>

                <input
                    type="search"
                    value={searchQuery}
                    placeholder="채팅 검색..."
                    aria-label="채팅 검색"
                    onChange={(event) => {
                        setSearchQuery(event.target.value);
                    }}
                    onKeyDown={(event) => {
                        if (
                            event.key === "Escape"
                            && searchQuery
                        ) {
                            event.preventDefault();
                            setSearchQuery("");
                        }
                    }}
                />

                {searchQuery && (
                    <button
                        type="button"
                        className="chat-room-search-clear"
                        aria-label="검색어 지우기"
                        title="검색어 지우기"
                        onClick={() => {
                            setSearchQuery("");
                        }}
                    >
                        ×
                    </button>
                )}
            </div>

            <div className="chat-room-list">
                {normalizedSearchQuery
                    && filteredRooms.length === 0
                    && (
                        <div className="chat-room-search-empty">
                            검색 결과가 없습니다.
                        </div>
                    )}

                {pinnedRooms.length > 0 && (
                    <>
                        <div className="chat-room-header">
                            Pinned
                        </div>
                        {pinnedRooms.map(renderRoom)}
                    </>
                )}

                {recentRooms.length > 0 && (
                    <>
                        <div className="chat-room-header">
                            Recent
                        </div>
                        {recentRooms.map(renderRoom)}
                    </>
                )}
            </div>
        </section>
    );
}

export default ChatRoomList;
