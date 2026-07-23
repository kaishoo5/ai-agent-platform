import axios from "axios";

import type {ChatMessage, ChatMessageRole, ChatRoomCreateRequest, ChatRoomResponse,} from "../types/chat";

interface ChatMessageResponse {
    id: string;
    roomId: string;
    role: string;
    content: string;
    createdAt: string;
}

const chatApi = axios.create({
    baseURL: "http://localhost:8080/api/chat",
    headers: {
        "Content-Type": "application/json",
    },
});

function convertMessageRole(
    role: string,
): ChatMessageRole {
    if (role.toLowerCase() === "user") {
        return "USER";
    }

    return "ASSISTANT";
}

export async function getChatRooms(): Promise<ChatRoomResponse[]> {
    const response = await chatApi.get<ChatRoomResponse[]>(
        "/rooms",
    );

    return response.data;
}

export async function createChatRoom(
    request: ChatRoomCreateRequest,
): Promise<ChatRoomResponse> {
    const response = await chatApi.post<ChatRoomResponse>(
        "/rooms",
        request,
    );

    return response.data;
}

export async function getChatRoomMessages(
    roomId: string,
): Promise<ChatMessage[]> {
    const response = await chatApi.get<ChatMessageResponse[]>(
        `/rooms/${roomId}/messages`,
    );

    return response.data.map((message) => ({
        ...message,
        role: convertMessageRole(message.role),
    }));
}

export async function deleteChatRoom(
    roomId: string,
): Promise<void> {
    await chatApi.delete(
        `/rooms/${roomId}`,
    );
}