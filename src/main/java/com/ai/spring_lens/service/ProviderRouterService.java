package com.ai.spring_lens.service;

import com.ai.spring_lens.client.GroqProviderClient;
import com.ai.spring_lens.client.OllamaProviderClient;
import com.ai.spring_lens.client.OpenAiProviderClient;
import com.ai.spring_lens.client.dto.ProviderResponse;
import com.ai.spring_lens.security.TenantContext;
import com.ai.spring_lens.service.BudgetEnforcementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class ProviderRouterService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouterService.class);

    private final GroqProviderClient groq;
    private final BudgetEnforcementService budgetService;
    private final OpenAiProviderClient openai;
    private final OllamaProviderClient ollama;

    public ProviderRouterService(
            GroqProviderClient groq,
            BudgetEnforcementService budgetService, OpenAiProviderClient openai, OllamaProviderClient ollama
    ) {
        this.groq = groq;
        this.budgetService = budgetService;
        this.openai = openai;
        this.ollama = ollama;
    }

    /**
     * Executes chat using a fallback chain:
     * GROQ → OpenAI → Ollama.
     *
     * <p>Each provider is tried sequentially on failure.
     * Returns the first successful response, or errors if all fail.
     */
    public Mono<ProviderResponse> executeChat(
            TenantContext ctx,
            String message,
            boolean useMemory,
            String conversationId
    ) {
        return groq.chat(message, useMemory, conversationId)
                .doOnSubscribe(s ->
                        log.info("Tenant={} Trying=GROQ", ctx.tenantId()))

                .onErrorResume(ex -> {
                    log.warn("Tenant={} GROQ failed → OPENAI fallback: {}",
                            ctx.tenantId(), ex.getMessage());

                    return openai.chat(message, useMemory, conversationId)
                            .doOnSubscribe(s ->
                                    log.info("Tenant={} Trying=OPENAI", ctx.tenantId()));
                })

                .onErrorResume(ex -> {
                    log.warn("Tenant={} OPENAI failed → OLLAMA fallback: {}",
                            ctx.tenantId(), ex.getMessage());

                    return ollama.chat(message, useMemory, conversationId)
                            .doOnSubscribe(s ->
                                    log.info("Tenant={} Trying=OLLAMA", ctx.tenantId()));
                })
                .doOnNext(r ->
                        log.info("Tenant={} Final provider={}", ctx.tenantId(), r.model()))
                .switchIfEmpty(Mono.error(new RuntimeException("All providers failed")));
    }

    /**
     * Executes streaming chat using a fallback chain:
     * GROQ → OpenAI.
     *
     * <p>Streams responses from the first successful provider.
     * Falls back to OpenAI if GROQ streaming fails.
     * Logs lifecycle events (start, completion, failure).
     */
    public Flux<String> executeStream(
            TenantContext ctx,
            String message
    ) {
        return groq.stream(message)
                .doOnSubscribe(s ->
                        log.info("Tenant={} Streaming Trying=GROQ", ctx.tenantId()))

                .onErrorResume(ex -> {
                    log.warn("Tenant={} GROQ stream failed → OPENAI fallback: {}",
                            ctx.tenantId(), ex.getMessage());

                    return openai.stream(message)
                            .doOnSubscribe(s ->
                                    log.info("Tenant={} Streaming Trying=OPENAI", ctx.tenantId()));
                })

                .doOnComplete(() ->
                        log.info("Tenant={} Streaming completed", ctx.tenantId()))

                .doOnError(e ->
                        log.warn("Tenant={} Streaming failed completely: {}",
                                ctx.tenantId(), e.getMessage()));
    }
}