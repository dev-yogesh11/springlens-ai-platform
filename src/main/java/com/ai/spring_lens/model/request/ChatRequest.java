package com.ai.spring_lens.model.request;


import java.util.UUID;

public record ChatRequest(
        String message,
        String conversationId
) {
    public ChatRequest(String message) {
        this(message, UUID.randomUUID().toString());
    }
}
