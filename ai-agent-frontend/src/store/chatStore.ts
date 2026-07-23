import {create} from "zustand";

import {createChatRoom, deleteChatRoom, getChatRoomMessages, getChatRooms,} from "../api/chatApi";
import type {ChatMessage, ChatRoom,} from "../types/chat";

interface ChatStore {
    rooms: ChatRoom[];

    activeRoomId: string | null;

    isLoadingRooms: boolean;

    isInitialized: boolean;

    isGenerating: boolean;

    abortController: AbortController | null;

    startGenerating: (
        abortController: AbortController,
    ) => void;

    finishGenerating: () => void;

    stopGenerating: () => void;

    loadRooms: () => Promise<void>;

    createRoom: () => Promise<string>;

    setActiveRoom: (
        roomId: string,
    ) => Promise<void>;

    addMessage: (
        roomId: string,
        message: ChatMessage,
    ) => void;

    appendMessageContent: (
        roomId: string,
        messageId: string,
        content: string,
    ) => void;

    updateMessageContent: (
        roomId: string,
        messageId: string,
        content: string,
    ) => void;

    replaceMessages: (
        roomId: string,
        messages: ChatMessage[],
    ) => void;

    clearMessages: (
        roomId: string,
    ) => void;

    deleteRoom: (
        roomId: string,
    ) => Promise<void>;

    refreshRooms: () => Promise<void>;
}

export const useChatStore = create<ChatStore>((
    set,
    get,
) => ({
    rooms: [],

    activeRoomId: null,

    isLoadingRooms: false,

    isInitialized: false,

    isGenerating: false,

    abortController: null,

    startGenerating: (
        abortController,
    ) => {
        set({
            isGenerating: true,
            abortController,
        });
    },

    finishGenerating: () => {
        set({
            isGenerating: false,
            abortController: null,
        });
    },

    stopGenerating: () => {
        const abortController = get().abortController;

        abortController?.abort();
    },

    loadRooms: async () => {
        const {
            isLoadingRooms,
            isInitialized,
        } = get();

        if (
            isLoadingRooms
            || isInitialized
        ) {
            return;
        }

        set({
            isLoadingRooms: true,
        });

        try {
            const roomResponses = await getChatRooms();

            const rooms: ChatRoom[] = roomResponses.map(
                (room) => ({
                    ...room,
                    messages: [],
                }),
            );

            if (rooms.length === 0) {
                set({
                    rooms: [],
                    activeRoomId: null,
                    isInitialized: true,
                });

                await get().createRoom();

                return;
            }

            const activeRoomId = rooms[0].id;

            set({
                rooms,
                activeRoomId,
                isInitialized: true,
            });

            await get().setActiveRoom(
                activeRoomId,
            );
        } catch (error) {
            console.error(
                "채팅방 목록을 불러오는 중 오류가 발생했습니다.",
                error,
            );

            set({
                isInitialized: true,
            });
        } finally {
            set({
                isLoadingRooms: false,
            });
        }
    },

    refreshRooms: async () => {
        try {
            const roomResponses = await getChatRooms();

            set((state) => ({
                rooms: roomResponses.map((roomResponse) => {
                    const existingRoom = state.rooms.find(
                        (room) => room.id === roomResponse.id,
                    );

                    return {
                        ...roomResponse,
                        messages: existingRoom?.messages ?? [],
                    };
                }),
            }));
        } catch (error) {
            console.error(
                "채팅방 목록 갱신 중 오류가 발생했습니다.",
                error,
            );
        }
    },

    createRoom: async () => {
        const {
            rooms,
            activeRoomId,
        } = get();

        const activeRoom = rooms.find(
            (room) => room.id === activeRoomId,
        );

        if (
            activeRoom
            && activeRoom.messages.length === 0
        ) {
            return activeRoom.id;
        }

        const roomResponse = await createChatRoom({
            title: "새 채팅",
        });

        const room: ChatRoom = {
            ...roomResponse,
            messages: [],
        };

        set((state) => ({
            rooms: [
                room,
                ...state.rooms,
            ],
            activeRoomId: room.id,
        }));

        return room.id;
    },

    setActiveRoom: async (
        roomId,
    ) => {
        const roomExists = get().rooms.some(
            (room) => room.id === roomId,
        );

        if (!roomExists) {
            return;
        }

        set({
            activeRoomId: roomId,
        });

        try {
            const messages = await getChatRoomMessages(
                roomId,
            );

            get().replaceMessages(
                roomId,
                messages,
            );
        } catch (error) {
            console.error(
                "채팅 메시지를 불러오는 중 오류가 발생했습니다.",
                error,
            );
        }
    },

    addMessage: (
        roomId,
        message,
    ) => {
        set((state) => ({
            rooms: state.rooms.map((room) => {
                if (room.id !== roomId) {
                    return room;
                }

                return {
                    ...room,
                    messages: [
                        ...room.messages,
                        message,
                    ],
                };
            }),
        }));
    },

    appendMessageContent: (
        roomId,
        messageId,
        content,
    ) => {
        set((state) => ({
            rooms: state.rooms.map((room) => {
                if (room.id !== roomId) {
                    return room;
                }

                return {
                    ...room,
                    messages: room.messages.map((message) => {
                        if (message.id !== messageId) {
                            return message;
                        }

                        return {
                            ...message,
                            content: message.content + content,
                        };
                    }),
                };
            }),
        }));
    },

    updateMessageContent: (
        roomId,
        messageId,
        content,
    ) => {
        set((state) => ({
            rooms: state.rooms.map((room) => {
                if (room.id !== roomId) {
                    return room;
                }

                return {
                    ...room,
                    messages: room.messages.map((message) => {
                        if (message.id !== messageId) {
                            return message;
                        }

                        return {
                            ...message,
                            content,
                        };
                    }),
                };
            }),
        }));
    },

    replaceMessages: (
        roomId,
        messages,
    ) => {
        set((state) => ({
            rooms: state.rooms.map((room) => {
                if (room.id !== roomId) {
                    return room;
                }

                return {
                    ...room,
                    messages,
                };
            }),
        }));
    },

    clearMessages: (
        roomId,
    ) => {
        set((state) => ({
            rooms: state.rooms.map((room) => {
                if (room.id !== roomId) {
                    return room;
                }

                return {
                    ...room,
                    messages: [],
                };
            }),
        }));
    },

    deleteRoom: async (
        roomId,
    ) => {
        try {
            await deleteChatRoom(
                roomId,
            );

            const remainingRooms = get().rooms.filter(
                (room) => room.id !== roomId,
            );

            if (remainingRooms.length === 0) {
                set({
                    rooms: [],
                    activeRoomId: null,
                });

                await get().createRoom();

                return;
            }

            const activeRoomId = get().activeRoomId;

            if (activeRoomId !== roomId) {
                set({
                    rooms: remainingRooms,
                });

                return;
            }

            const nextRoomId = remainingRooms[0].id;

            set({
                rooms: remainingRooms,
                activeRoomId: nextRoomId,
            });

            await get().setActiveRoom(
                nextRoomId,
            );
        } catch (error) {
            console.error(
                "채팅방 삭제 중 오류가 발생했습니다.",
                error,
            );
        }
    },
}));