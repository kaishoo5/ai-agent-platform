import axios from "axios";
import API_BASE_URL from "../config/api";

import type {
    ChatMessage,
    ChatMessageRole,
    ChatRoomCreateRequest,
    ChatRoomResponse,
    ChatSource,
    VideoSummaryResult
} from "../types/chat";

interface ChatMessageResponse {
    id: string;
    roomId: string;
    role: string;
    content: string;
    videoResult: string | null;
    sourceResult: string | null;
    createdAt: string;
}

const chatApi = axios.create({
    baseURL: `${API_BASE_URL}/api/chat`,
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

function parseSources(
    sourceResult: string | null,
): ChatSource[] {
    if (!sourceResult) {
        return [];
    }

    try {
        const parsed =
            JSON.parse(
                sourceResult,
            ) as unknown;

        if (!Array.isArray(parsed)) {
            return [];
        }

        return parsed as ChatSource[];
    } catch (error) {
        console.error(
            "RAG 출처 결과를 파싱하는 중 오류가 발생했습니다.",
            error,
        );

        return [];
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
        id: message.id,
        roomId: message.roomId,
        role: convertMessageRole(
            message.role,
        ),
        content: message.content,
        videoResult: parseVideoResult(
            message.videoResult,
        ),
        sources: parseSources(
            message.sourceResult,
        ),
        createdAt: message.createdAt,
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
        `${API_BASE_URL}/api/files`,
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
            `${API_BASE_URL}/api/files`,
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
        `${API_BASE_URL}/api/files/${fileId}`,
        {
            params: {
                roomId,
            },
        },
    );
}