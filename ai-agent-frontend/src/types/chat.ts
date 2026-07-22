export type ChatRole = "USER" | "ASSISTANT";

export interface ChatMessage {

    id: string;

    role: ChatRole;

    content: string;

    createdAt: string;

}

export interface ChatRoom {

    id: string;

    title: string;

    createdAt: string;

}

export interface ChatRequest {

    message: string;

}

export interface ChatResponse {

    message: string;

}