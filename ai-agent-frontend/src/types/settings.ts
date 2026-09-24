export interface RuntimeSettings {
    backendStatus: "UP" | "DOWN";
    ollamaStatus: "UP" | "DOWN";
    ollamaEndpoint: string;
    textModel: string;
    visionModel: string;
    embeddingModel: string;
    installedModels: string[];
}

export interface ModelSettingsUpdateRequest {
    textModel: string;
    visionModel: string;
    embeddingModel: string;
}


export interface AgentMemory {
    id: string;
    category: string;
    content: string;
    createdAt: string;
    updatedAt: string;
}

export interface AgentMemoryUpdateRequest {
    category: string;
    content: string;
}

export interface AgentMemoryEnabledResponse {
    enabled: boolean;
}
