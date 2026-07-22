import type {ChatRequest} from "../types/chat";

type ChunkHandler = (chunk: string) => void;

class ChatStreamService {

    async sendMessage(
        request: ChatRequest,
        onChunk: ChunkHandler,
    ): Promise<void> {
        const response = await fetch(
            "http://localhost:8080/api/chat/stream",
            {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Accept": "text/event-stream",
                },
                body: JSON.stringify(request),
            },
        );

        if (!response.ok) {
            throw new Error(
                `채팅 스트리밍 요청 실패: ${response.status}`,
            );
        }

        if (!response.body) {
            throw new Error("스트리밍 응답 본문이 없습니다.");
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");

        let buffer = "";

        try {
            while (true) {
                const {
                    done,
                    value,
                } = await reader.read();

                if (done) {
                    break;
                }

                buffer += decoder.decode(
                    value,
                    {
                        stream: true,
                    },
                );

                const events = buffer.split("\n\n");

                buffer = events.pop() ?? "";

                for (const eventBlock of events) {
                    const eventName = this.parseField(
                        eventBlock,
                        "event",
                    );

                    const data = this.parseField(
                        eventBlock,
                        "data",
                    );

                    if (eventName === "message") {
                        onChunk(JSON.parse(data));
                    }

                    if (eventName === "done") {
                        return;
                    }
                }
            }
        } finally {
            reader.releaseLock();
        }
    }

    private parseField(
        eventBlock: string,
        fieldName: string,
    ): string {
        const prefix = `${fieldName}:`;

        return eventBlock
            .split("\n")
            .filter((line) => line.startsWith(prefix))
            .map((line) => line.slice(prefix.length))
            .join("\n");
    }

}

export default new ChatStreamService();