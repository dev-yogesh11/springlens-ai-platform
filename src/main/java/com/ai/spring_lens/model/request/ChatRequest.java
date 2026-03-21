package com.ai.spring_lens.model.request;

import java.util.UUID;

public record ChatRequest(
        String message,
        String conversationId,
        String retrievalStrategy   // null = use springlens.retrieval.default-strategy
) {
    // Convenience constructor — auto-generates conversationId, uses config default strategy
    public ChatRequest(String message) {
        this(message, UUID.randomUUID().toString(), null);
    }

    // Convenience constructor — auto-generates conversationId, caller specifies strategy
    public ChatRequest(String message, String conversationId) {
        this(message, conversationId, null);
    }
}
