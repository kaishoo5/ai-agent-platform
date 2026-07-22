import api from "../api/api";

import type {ChatRequest, ChatResponse,} from "../types/chat";

class ChatService {

    async sendMessage(
        request: ChatRequest,
    ): Promise<ChatResponse> {

        const { data } = await api.post<ChatResponse>(
            "/api/chat",
            request,
        );

        return data;

    }

}

export default new ChatService();