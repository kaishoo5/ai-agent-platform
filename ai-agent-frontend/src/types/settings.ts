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
