package com.ai.spring_lens.client;

import com.ai.spring_lens.client.dto.ProviderResponse;
import com.ai.spring_lens.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Service
public class OpenAiProviderClient implements ProviderClient {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final LlmProperties properties;

    public OpenAiProviderClient(
            @Qualifier("openAiChatClient") ChatClient chatClient,
            ChatMemory chatMemory, LlmProperties properties
    ) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.properties = properties;
    }

    @Override
    public Mono<ProviderResponse> chat(
            String message,
            boolean useMemory,
            String conversationId
    ) {
        long start = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
            var prompt = chatClient.prompt().user(message);

                    if (useMemory) {
                        prompt = prompt.advisors(
                                MessageChatMemoryAdvisor.builder(chatMemory)
                                        .conversationId(conversationId != null ? conversationId : "default")
                                        .build()
                        );
                    }
                    log.info("OpenAI baseUrl={}", properties.getOpenai().getBaseUrl());
                    log.info("Chat conversation ID : {}",conversationId);

            var response = prompt.call().chatResponse();

            log.info("Open AI RAw response {}",response);

            var metadata = response.getMetadata();
            var usage = metadata != null ? metadata.getUsage() : null;

            return new ProviderResponse(
                    response.getResult().getOutput().getText(),
                    metadata != null ? metadata.getModel() : "openai",
                    usage != null ? usage.getPromptTokens() : 0,
                    usage != null ? usage.getCompletionTokens() : 0,
                    usage != null ? usage.getTotalTokens() : 0,
                    System.currentTimeMillis() - start
            );
        })
        .timeout(properties.getOpenai().getTimeout());
    }

    @Override
    public Flux<String> stream(String message) {
        return chatClient.prompt().user(message).stream().content();
    }

    @Override
    public String name() {
        return "OPENAI";
    }
}