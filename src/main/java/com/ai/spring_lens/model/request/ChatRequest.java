package com.ai.spring_lens.model.request;

import java.util.UUID;

public record ChatRequest(
        String message,
        String conversationId,
        String retrievalStrategy,  // null = use springlens.retrieval.default-strategy
        Boolean memoryEnabled      // null = use springlens.chat.memory.enabled-by-default
) {
    // Convenience constructor — auto-generates conversationId, uses all config defaults
    public ChatRequest(String message) {
        this(message, UUID.randomUUID().toString(), null, null);
    }

    // Convenience constructor — caller specifies conversationId, uses config defaults
    public ChatRequest(String message, String conversationId) {
        this(message, conversationId, null, null);
    }

    // Convenience constructor — caller specifies strategy, uses config defaults for rest
    public ChatRequest(String message, String conversationId, String retrievalStrategy) {
        this(message, conversationId, retrievalStrategy, null);
    }
}