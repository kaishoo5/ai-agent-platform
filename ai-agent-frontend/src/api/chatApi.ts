import axios from "axios";
import API_BASE_URL from "../config/api";

import type {
    ChatMessage,
    ChatMessageRole,
    ChatRoomCreateRequest,
    ChatRoomPinnedUpdateRequest,
    ChatRoomResponse,
    ChatRoomTitleUpdateRequest,
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
): VideoSummaryResult[] {
    if (!videoResult) {
        return [];
    }

    try {
        const parsed =
            JSON.parse(
                videoResult,
            ) as unknown;

        if (Array.isArray(parsed)) {
            return parsed as VideoSummaryResult[];
        }

        if (
            parsed
            && typeof parsed === "object"
        ) {
            return [
                parsed as VideoSummaryResult,
            ];
        }

        return [];
    } catch (error) {
        console.error(
            "영상 결과를 파싱하는 중 오류가 발생했습니다.",
            error,
        );

        return [];
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

export async function updateChatRoomTitle(
    roomId: string,
    request: ChatRoomTitleUpdateRequest,
): Promise<ChatRoomResponse> {
    const response = await chatApi.patch<ChatRoomResponse>(
        `/rooms/${roomId}/title`,
        request,
    );

    return response.data;
}

export async function updateChatRoomPinned(
    roomId: string,
    request: ChatRoomPinnedUpdateRequest,
): Promise<ChatRoomResponse> {
    const response = await chatApi.patch<ChatRoomResponse>(
        `/rooms/${roomId}/pinned`,
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


export async function editChatMessage(
    roomId: string,
    messageId: string,
    content: string,
): Promise<ChatMessage[]> {
    const response = await chatApi.patch<ChatMessageResponse[]>(
        `/rooms/${roomId}/messages/${messageId}`,
        {
            content,
        },
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

export type ChatFileStatus =
    | "UPLOADED"
    | "ANALYZING"
    | "COMPLETED"
    | "FAILED"
    | "CANCELLED";

export interface ChatFileUploadResponse {
    id: string;
    roomId: string;
    originalName: string;
    contentType: string | null;
    extension: string;
    size: number;
    status: ChatFileStatus;
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
    status: ChatFileStatus;
    createdAt: string;
}

export type FileAnalysisStepStatus =
    | "running"
    | "completed"
    | "failed";

export interface FileAnalysisStep {
    code: string;
    status: FileAnalysisStepStatus;
    message: string;
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

export async function cancelChatFileAnalysis(
    fileId: string,
): Promise<void> {
    await axios.post(
        `${API_BASE_URL}/api/files/${fileId}/cancel`,
    );
}

export function streamFileAnalysisProgress(
    fileId: string,
    onStep: (step: FileAnalysisStep) => void,
    signal?: AbortSignal,
): Promise<void> {
    return new Promise((resolve, reject) => {
        const eventSource =
            new EventSource(
                `${API_BASE_URL}/api/files/${fileId}/progress`,
            );

        let settled = false;

        const cleanup = (): void => {
            eventSource.close();

            if (signal) {
                signal.removeEventListener(
                    "abort",
                    handleAbort,
                );
            }
        };

        const complete = (): void => {
            if (settled) {
                return;
            }

            settled = true;
            cleanup();
            resolve();
        };

        const fail = (
            error: Error,
        ): void => {
            if (settled) {
                return;
            }

            settled = true;
            cleanup();
            reject(error);
        };

        const handleAbort = (): void => {
            fail(
                new DOMException(
                    "파일 분석 대기가 중단되었습니다.",
                    "AbortError",
                ),
            );
        };

        eventSource.addEventListener(
            "file_step",
            (event) => {
                try {
                    const step =
                        JSON.parse(
                            (event as MessageEvent<string>).data,
                        ) as FileAnalysisStep;

                    onStep(
                        step,
                    );

                    if (
                        step.code === "file_analysis"
                        && step.status === "completed"
                    ) {
                        complete();
                        return;
                    }

                    if (
                        step.code === "file_analysis"
                        && step.status === "failed"
                    ) {
                        fail(
                            new Error(
                                step.message,
                            ),
                        );
                    }
                } catch (error) {
                    fail(
                        error instanceof Error
                            ? error
                            : new Error(
                                "파일 분석 진행 상태를 처리하지 못했습니다.",
                            ),
                    );
                }
            },
        );

        eventSource.onerror = () => {
            if (settled) {
                return;
            }

            fail(
                new Error(
                    "파일 분석 진행 상태 연결이 종료되었습니다.",
                ),
            );
        };

        if (signal) {
            if (signal.aborted) {
                handleAbort();
                return;
            }

            signal.addEventListener(
                "abort",
                handleAbort,
                {
                    once: true,
                },
            );
        }
    });
}