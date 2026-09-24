import api from "./api";
import type {
    AgentMemory,
    AgentMemoryEnabledResponse,
    AgentMemoryUpdateRequest,
    ModelSettingsUpdateRequest,
    RuntimeSettings,
} from "../types/settings";

export async function getRuntimeSettings(): Promise<RuntimeSettings> {
    const response = await api.get<RuntimeSettings>(
        "/api/settings/runtime",
    );

    return response.data;
}

export async function updateModelSettings(
    request: ModelSettingsUpdateRequest,
): Promise<RuntimeSettings> {
    const response = await api.put<RuntimeSettings>(
        "/api/settings/models",
        request,
    );

    return response.data;
}


export async function getAgentMemories(): Promise<AgentMemory[]> {
    const response = await api.get<AgentMemory[]>(
        "/api/settings/memory",
    );

    return response.data;
}

export async function getAgentMemoryEnabled(): Promise<boolean> {
    const response = await api.get<AgentMemoryEnabledResponse>(
        "/api/settings/memory/enabled",
    );

    return response.data.enabled;
}

export async function updateAgentMemoryEnabled(
    enabled: boolean,
): Promise<boolean> {
    const response = await api.put<AgentMemoryEnabledResponse>(
        "/api/settings/memory/enabled",
        {
            enabled,
        },
    );

    return response.data.enabled;
}

export async function updateAgentMemory(
    memoryId: string,
    request: AgentMemoryUpdateRequest,
): Promise<AgentMemory> {
    const response = await api.put<AgentMemory>(
        `/api/settings/memory/${memoryId}`,
        request,
    );

    return response.data;
}

export async function deleteAgentMemory(
    memoryId: string,
): Promise<void> {
    await api.delete(
        `/api/settings/memory/${memoryId}`,
    );
}

export async function deleteAllAgentMemories(): Promise<void> {
    await api.delete(
        "/api/settings/memory",
    );
}
