import axios from "axios";

import type {
    ChatMessage,
    ChatMessageRole,
    ChatRoomCreateRequest,
    ChatRoomResponse,
    VideoSummaryResult
} from "../types/chat";

interface ChatMessageResponse {
    id: string;
    roomId: string;
    role: string;
    content: string;
    videoResult: string | null;
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

function parseVideoResult(
    videoResult: string | null,
): VideoSummaryResult | null {
    if (!videoResult) {
        return null;
    }

    try {
        return JSON.parse(
            videoResult,
        ) as VideoSummaryResult;
    } catch (error) {
        console.error(
            "영상 요약 결과를 파싱하는 중 오류가 발생했습니다.",
            error,
        );

        return null;
    }
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
        role: convertMessageRole(
            message.role,
        ),
        videoResult: parseVideoResult(
            message.videoResult,
        ),
    }));
}

export async function deleteChatRoom(
    roomId: string,
): Promise<void> {
    await chatApi.delete(
        `/rooms/${roomId}`,
    );
}

export interface ChatFileUploadResponse {
    id: string;
    roomId: string;
    originalName: string;
    contentType: string | null;
    extension: string;
    size: number;
}

export async function uploadChatFile(
    roomId: string,
    file: File,
): Promise<ChatFileUploadResponse> {
    const formData = new FormData();

    formData.append(
        "roomId",
        roomId,
    );

    formData.append(
        "file",
        file,
    );

    const response = await axios.post<ChatFileUploadResponse>(
        "http://localhost:8080/api/files",
        formData,
    );

    return response.data;
}

export interface ChatFileResponse {
    id: string;
    roomId: string;
    originalName: string;
    contentType: string | null;
    extension: string;
    size: number;
    status: string;
    createdAt: string;
}

export async function getChatFiles(
    roomId: string,
): Promise<ChatFileResponse[]> {

    const response =
        await axios.get<ChatFileResponse[]>(
            "http://localhost:8080/api/files",
            {
                params: {
                    roomId,
                },
            },
        );

    return response.data;
}

export async function deleteChatFile(
    roomId: string,
    fileId: string,
): Promise<void> {

    await axios.delete(
        `http://localhost:8080/api/files/${fileId}`,
        {
            params: {
                roomId,
            },
        },
    );
}