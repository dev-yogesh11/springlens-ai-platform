package com.ai.spring_lens.client;

import com.ai.spring_lens.client.dto.GroqRequest;
import com.ai.spring_lens.client.dto.GroqResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class GroqClient {

    private final WebClient webClient;
    private final String model;

    public GroqClient(
            WebClient.Builder webClientBuilder,
            @Value("${spring.ai.openai.groq-url}") String apiUrl,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${spring.ai.openai.api-key}") String apiKey
    ) {
        this.model = model;
        this.webClient = webClientBuilder
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public Mono<GroqResponse> chat(String userMessage, String systemPrompt) {
        String requestId = UUID.randomUUID().toString();
        GroqRequest request = new GroqRequest(
                model,
                List.of(
                        new GroqRequest.Message("system", systemPrompt),
                        new GroqRequest.Message("user", userMessage)
                )
        );

        log.info("Groq request [{}] model={} userMessage={}",
                requestId, model, userMessage);

        return webClient.post()
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("Groq API error [{}] status={} body={}",
                                            requestId, response.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException(
                                            "Groq API error: " + response.statusCode()
                                    ));
                                })
                )
                .bodyToMono(GroqResponse.class)
                .doOnSuccess(response -> log.info(
                        "Groq response [{}] promptTokens={} completionTokens={} totalTokens={}",
                        requestId,
                        response.usage().promptTokens(),
                        response.usage().completionTokens(),
                        response.usage().totalTokens()
                ))
                .doOnError(error -> log.error(
                        "Groq request failed [{}] error={}", requestId, error.getMessage()
                ));
    }
}