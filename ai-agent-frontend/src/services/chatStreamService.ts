import type {ChatMessage} from "../types/chat";

interface ChatStreamRequestMessage {
    role: "user" | "assistant";
    content: string;
}

interface ChatStreamRequest {
    roomId: string;
    messages: ChatStreamRequestMessage[];
    regenerate: boolean;
    fileIds: string[];
}

function convertRole(
    role: ChatMessage["role"],
): "user" | "assistant" {
    return role === "USER"
        ? "user"
        : "assistant";
}

export async function streamChat(
    roomId: string,
    messages: ChatMessage[],
    onChunk: (
        chunk: string,
    ) => void,
    signal?: AbortSignal,
    regenerate = false,
    fileIds: string[] = [],
): Promise<void> {
    const request: ChatStreamRequest = {
        roomId,
        messages: messages.map((message) => ({
            role: convertRole(message.role),
            content: message.content,
        })),
        regenerate,
        fileIds,
    };

    const response = await fetch(
        "http://localhost:8080/api/chat/stream",
        {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
            },
            body: JSON.stringify(request),
            signal,
        },
    );

    if (!response.ok) {
        const responseText = await response.text();

        throw new Error(
            `채팅 요청에 실패했습니다. `
            + `status=${response.status}, `
            + `body=${responseText}`,
        );
    }

    if (!response.body) {
        throw new Error(
            "스트리밍 응답 본문이 없습니다.",
        );
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");

    let buffer = "";

    try {
        while (true) {
            const {
                value,
                done,
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

            buffer = buffer.replace(/\r\n/g, "\n");

            const eventBlocks = buffer.split("\n\n");

            buffer = eventBlocks.pop() ?? "";

            for (const eventBlock of eventBlocks) {
                const lines = eventBlock.split("\n");

                let eventName = "";
                let data = "";

                for (const line of lines) {
                    if (line.startsWith("event:")) {
                        eventName = line
                            .slice("event:".length)
                            .trim();

                        continue;
                    }

                    if (line.startsWith("data:")) {
                        data += line
                            .slice("data:".length)
                            .trimStart();
                    }
                }

                if (
                    eventName === "message"
                    && data
                ) {
                    const chunk = JSON.parse(data) as string;

                    onChunk(chunk);
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