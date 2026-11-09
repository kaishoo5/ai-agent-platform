package com.agent.aiagent.provider.chat;

public enum ModelProviderType {

    OLLAMA,
    GEMINI;

    public static ModelProviderType fromModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException(
                    "모델 ID가 비어 있습니다."
            );
        }

        if (modelId.startsWith("gemini:")) {
            return GEMINI;
        }

        if (modelId.startsWith("ollama:")) {
            return OLLAMA;
        }

        // 기존 DB에 저장된 Ollama 모델명과 호환
        return OLLAMA;
    }

    public static String resolveModelName(String modelId) {
        if (modelId.startsWith("gemini:")) {
            return modelId.substring("gemini:".length());
        }

        if (modelId.startsWith("ollama:")) {
            return modelId.substring("ollama:".length());
        }

        return modelId;
    }
}
