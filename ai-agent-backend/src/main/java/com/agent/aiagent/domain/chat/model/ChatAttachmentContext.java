package com.agent.aiagent.domain.chat.model;

import java.util.List;

public record ChatAttachmentContext(
        List<String> documentFileIds,
        List<String> encodedImages
) {

    public boolean hasImages() {
        return encodedImages != null
                && !encodedImages.isEmpty();
    }

    public boolean isEmpty() {
        return (
                documentFileIds == null
                        || documentFileIds.isEmpty()
        ) && (
                encodedImages == null
                        || encodedImages.isEmpty()
        );
    }
}