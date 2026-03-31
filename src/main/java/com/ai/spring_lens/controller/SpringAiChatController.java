package com.ai.spring_lens.controller;

import com.ai.spring_lens.model.request.ChatRequest;
import com.ai.spring_lens.model.response.ErrorResponse;
import com.ai.spring_lens.security.TenantContext;
import com.ai.spring_lens.service.BudgetEnforcementService;
import com.ai.spring_lens.service.SpringAiChatService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Chat controller — all endpoints require JWT authentication.
 *
 * TenantContext is extracted from Reactor Context (populated by
 * JwtAuthenticationFilter) and passed explicitly to service methods.
 * This keeps service layer testable — no Reactor Context dependency in service.
 *
 * Three endpoints:
 * - POST /chat     — pure vector baseline, configurable memory
 * - GET  /stream   — streaming, configurable strategy, stateless
 * - POST /query    — primary endpoint, configurable strategy + memory
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
public class SpringAiChatController {

    private final SpringAiChatService chatService;
    private final BudgetEnforcementService budgetEnforcementService;

    public SpringAiChatController(SpringAiChatService chatService, BudgetEnforcementService budgetEnforcementService) {
        this.chatService = chatService;
        this.budgetEnforcementService = budgetEnforcementService;
    }

    /**
     * Reads TenantContext from Reactor Context.
     * Populated by JwtAuthenticationFilter on every authenticated request.
     * Returns error Mono if context is missing — should never happen
     * since SecurityConfig requires authentication on all chat endpoints.
     */
    private Mono<TenantContext> tenantContext() {
        return Mono.deferContextual(ctx -> {
            TenantContext tenantContext = ctx.getOrDefault(
                    TenantContext.CONTEXT_KEY, null);
            if (tenantContext == null) {
                return Mono.error(new IllegalStateException(
                        "TenantContext missing from Reactor Context"));
            }
            return Mono.just(tenantContext);
        });
    }

    /**
     * Runs budget enforcement and returns result with warnings.
     * Throws 429 or 402 if any limit exceeded.
     * Returns BudgetCheckResult with warning strings for header injection.
     */
    private Mono<BudgetEnforcementService.BudgetCheckResult> checkBudget(
            TenantContext ctx) {
        return budgetEnforcementService.enforce(ctx);
    }

    /**
     * Adds budget warning headers to response if any limits approaching.
     * Header format: X-Budget-Warning: user-daily-requests at 85% (85/100)-- this is configurable
     */
    private ResponseEntity<Object> withWarningHeaders(
            ResponseEntity<Object> response,
            BudgetEnforcementService.BudgetCheckResult budgetResult) {
        if (!budgetResult.hasWarnings()) {
            return response;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(response.getHeaders());
        budgetResult.warnings().forEach(warning ->
                headers.add("X-Budget-Warning", warning));
        return new ResponseEntity<>(response.getBody(), headers,
                response.getStatusCode());
    }

    /**
     * Pure vector baseline — memory configurable, defaults to config value.
     * Always uses vector-only retrieval — preserved as Phase 1 comparison.
     */
    @PostMapping
    public Mono<ResponseEntity<Object>> chat(
            @RequestBody ChatRequest request
    ) {
        return tenantContext()
                .flatMap(ctx -> checkBudget(ctx)
                        .flatMap(budgetResult -> chatService.chat(
                                        request.message(),
                                        request.conversationId(),
                                        request.memoryEnabled(),
                                        ctx.tenantId(),
                                        ctx)
                                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                                .map(response -> withWarningHeaders(response, budgetResult))))
                .onErrorResume(ex -> {
                    if (ex instanceof ResponseStatusException rse) {
                        return Mono.just(ResponseEntity
                                .status(rse.getStatusCode())
                                .body((Object) new ErrorResponse(
                                        rse.getStatusCode().toString(),
                                        rse.getReason())));
                    }
                    String message = ex.getMessage() != null
                            ? ex.getMessage() : "Unexpected error occurred";
                    return Mono.just(ResponseEntity
                            .internalServerError()
                            .body((Object) new ErrorResponse("LLM_ERROR", message)));
                });
    }

    /**
     * Primary production endpoint — configurable strategy + configurable memory.
     * Tenant context extracted and passed explicitly to service.
     */
    @PostMapping("/query")
    public Mono<ResponseEntity<Object>> query(
            @RequestBody ChatRequest request
    ) {
        return tenantContext()
                .flatMap(ctx -> checkBudget(ctx)
                        .flatMap(budgetResult -> chatService.query(
                                        request.message(),
                                        request.retrievalStrategy(),
                                        request.memoryEnabled(),
                                        request.conversationId(),
                                        ctx.tenantId(),
                                        ctx)
                                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                                .map(response -> withWarningHeaders(response, budgetResult))))
                .onErrorResume(ex -> {
                    if (ex instanceof ResponseStatusException rse) {
                        return Mono.just(ResponseEntity
                                .status(rse.getStatusCode())
                                .body((Object) new ErrorResponse(
                                        rse.getStatusCode().toString(),
                                        rse.getReason())));
                    }
                    return Mono.just(ResponseEntity
                            .internalServerError()
                            .body((Object) new ErrorResponse(
                                    "QUERY_ERROR", ex.getMessage())));
                });
    }

    /**
     * Streaming — configurable strategy, always stateless, no memory.
     * Tenant context passed to service for document isolation and audit.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam String message,
            @RequestParam(required = false) String retrievalStrategy
    ) {
        return tenantContext()
                .flatMap(ctx -> checkBudget(ctx).thenReturn(ctx))
                .flatMapMany(ctx -> chatService.stream(
                        message,
                        retrievalStrategy,
                        ctx.tenantId(),
                        ctx))
                .onErrorResume(ex -> {
                    if (ex instanceof ResponseStatusException rse) {
                        return Flux.error(rse);
                    }
                    return Flux.just("Service temporarily unavailable.");
                });
    }

}