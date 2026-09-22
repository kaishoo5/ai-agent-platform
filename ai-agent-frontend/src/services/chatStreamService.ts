import type {ChatMessage, ChatSource, VideoSummaryResult} from "../types/chat";
import API_BASE_URL from "../config/api.ts";

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

export type AgentStepStatus =
    | "running"
    | "completed"
    | "failed";

export interface AgentStep {
    code: string;
    status: AgentStepStatus;
    message: string;
}

interface ChatStreamHandlers {
    onChunk: (
        chunk: string,
    ) => void;

    onVideoResult?: (
        videoResult: VideoSummaryResult[],
    ) => void;

    onSources?: (
        sources: ChatSource[],
    ) => void;

    onAgentStep?: (
        step: AgentStep,
    ) => void;
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
    handlers: ChatStreamHandlers,
    signal?: AbortSignal,
    regenerate = false,
    fileIds: string[] = [],
): Promise<void> {
    const request: ChatStreamRequest = {
        roomId,
        messages: messages.map((message) => ({
            role: convertRole(
                message.role,
            ),
            content: message.content,
        })),
        regenerate,
        fileIds,
    };

    const response = await fetch(
        `${API_BASE_URL}/api/chat/stream`,
        {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
            },
            body: JSON.stringify(
                request,
            ),
            signal,
        },
    );

    if (!response.ok) {
        const responseText =
            await response.text();

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

    const reader =
        response.body.getReader();

    const decoder =
        new TextDecoder(
            "utf-8",
        );

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

            buffer =
                buffer.replace(
                    /\r\n/g,
                    "\n",
                );

            const eventBlocks =
                buffer.split(
                    "\n\n",
                );

            buffer =
                eventBlocks.pop()
                ?? "";

            for (
                const eventBlock
                of eventBlocks
                ) {
                const lines =
                    eventBlock.split(
                        "\n",
                    );

                let eventName = "";
                let data = "";

                for (const line of lines) {
                    if (
                        line.startsWith(
                            "event:",
                        )
                    ) {
                        eventName =
                            line
                                .slice(
                                    "event:".length,
                                )
                                .trim();

                        continue;
                    }

                    if (
                        line.startsWith(
                            "data:",
                        )
                    ) {
                        data +=
                            line
                                .slice(
                                    "data:".length,
                                )
                                .trimStart();
                    }
                }

                if (
                    eventName === "agent_step"
                    && data
                ) {
                    const step =
                        JSON.parse(
                            data,
                        ) as AgentStep;

                    handlers.onAgentStep?.(
                        step,
                    );

                    continue;
                }

                if (
                    eventName === "message"
                    && data
                ) {
                    const chunk =
                        JSON.parse(
                            data,
                        ) as string;

                    handlers.onChunk(
                        chunk,
                    );

                    continue;
                }

                if (
                    eventName === "source_result"
                    && data
                ) {
                    const sources =
                        JSON.parse(
                            data,
                        ) as ChatSource[];

                    handlers.onSources?.(
                        sources,
                    );

                    continue;
                }

                if (
                    eventName === "video_result"
                    && data
                ) {
                    const parsed =
                        JSON.parse(
                            data,
                        ) as unknown;

                    const videoResult =
                        Array.isArray(parsed)
                            ? parsed as VideoSummaryResult[]
                            : [
                                parsed as VideoSummaryResult,
                            ];

                    handlers.onVideoResult?.(
                        videoResult,
                    );

                    continue;
                }

                if (
                    eventName === "done"
                ) {
                    return;
                }
            }
        }
    } finally {
        reader.releaseLock();
    }
}