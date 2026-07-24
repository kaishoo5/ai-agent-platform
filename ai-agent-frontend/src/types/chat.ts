export type ChatMessageRole =
    | "USER"
    | "ASSISTANT";

export type MessageRole =
    | "user"
    | "assistant";

export interface ChatMessage {
    id: string;
    roomId: string;
    role: ChatMessageRole;
    content: string;
    createdAt: string;
}

export interface ChatRoom {
    id: string;
    title: string;
    createdAt: string;
    updatedAt: string;
    messages: ChatMessage[];
    files: ChatFile[];
}

export interface ChatRoomResponse {
    id: string;
    title: string;
    createdAt: string;
    updatedAt: string;
}

export interface ChatRoomCreateRequest {
    title: string;
}

export interface ChatRequest {
    message: string;
}

export interface ChatResponse {
    message: string;
}

export interface ChatStreamRequest {
    roomId: string;
    messages: Array<{
        role: MessageRole;
        content: string;
    }>;
}

export interface ChatFile {
    id: string;
    roomId: string;
    originalName: string;
    contentType: string | null;
    extension: string;
    size: number;
    status: string;
    createdAt: string;
}