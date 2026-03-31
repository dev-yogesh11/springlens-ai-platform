package com.ai.spring_lens.service;

import com.ai.spring_lens.config.ChatMemoryProperties;
import com.ai.spring_lens.config.IngestionProperties;
import com.ai.spring_lens.config.RetrievalProperties;
import com.ai.spring_lens.model.response.ChatResponse;
import com.ai.spring_lens.model.response.QueryResponse;
import com.ai.spring_lens.service.strategy.RetrievalStrategy;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.ai.spring_lens.security.TenantContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SpringAiChatService {

    private static final String SYSTEM_PROMPT = """
                You are SpringLens, a helpful AI assistant.
                Answer questions based on the provided context.
                Always cite the source document and page number.
                If the answer is not in the context, say so clearly.
                """;

    private final ChatClient chatClient;
    private final CircuitBreaker circuitBreaker;
    private final VectorStore vectorStore;
    private final IngestionProperties properties;
    private final ChatMemory chatMemory;
    private final Map<String, RetrievalStrategy> retrievalStrategies;
    private final RetrievalProperties retrievalProperties;
    private final ChatMemoryProperties chatMemoryProperties;
    private final AuditService auditService;
    private final ProviderRouterService providerRouter;

    public SpringAiChatService(ChatClient.Builder builder,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               VectorStore vectorStore,
                               IngestionProperties properties,
                               ChatMemoryRepository chatMemoryRepository,
                               Map<String, RetrievalStrategy> retrievalStrategies,
                               RetrievalProperties retrievalProperties, ChatMemoryProperties chatMemoryProperties, AuditService auditService, ProviderRouterService providerRouter) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.retrievalStrategies = retrievalStrategies;
        this.retrievalProperties = retrievalProperties;
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.circuitBreaker = circuitBreakerRegistry
                .circuitBreaker("groqClient");
        this.chatMemoryProperties = chatMemoryProperties;
        this.auditService = auditService;
        this.providerRouter = providerRouter;
        log.info("ChatMemoryRepository implementation: {}",
                chatMemoryRepository.getClass().getName());
        log.info("Available retrieval strategies: {}",
                retrievalStrategies.keySet());
    }

    // ---------------------------------------------------------------
    // Strategy resolution — request param overrides config default
    // Falls back to default strategy(configurable) if unknown strategy name provided
    // ---------------------------------------------------------------

    private List<Document> retrieve(String query, String requestStrategy, UUID tenantId) {
        String strategyName = (requestStrategy != null && !requestStrategy.isBlank())
                ? requestStrategy
                : retrievalProperties.getDefaultStrategy();

        RetrievalStrategy strategy = retrievalStrategies.get(strategyName);

        if (strategy == null) {
            log.warn("Unknown retrieval strategy='{}', falling back to default strategy '{}'",
                    strategyName,retrievalProperties.getDefaultStrategy());
            strategy = retrievalStrategies.get(retrievalProperties.getDefaultStrategy());
        }

        log.info("Retrieval strategy='{}' query='{}'", strategyName, query);
        return strategy.retrieve(query, properties.getSimilarityThreshold(),tenantId);
    }

    // ---------------------------------------------------------------
    // Context builder — shared across all three methods
    // ---------------------------------------------------------------

    private String buildContext(List<Document> docs) {
        return docs.stream()
                .map(doc -> "Source: " + doc.getMetadata().getOrDefault(
                        "original_file_name", doc.getMetadata().get("file_name")) +
                        " (Page " + doc.getMetadata().get("page_number") + ")" +
                        System.lineSeparator() + "Content: " + doc.getText())
                .collect(Collectors.joining(
                        System.lineSeparator() + System.lineSeparator() +
                                "---" + System.lineSeparator() + System.lineSeparator()
                ));
    }

    private String buildAugmentedMessage(String message, List<Document> docs) {
        if (docs.isEmpty()) {
            return "The user asked: " + message +
                    "\n\nNo new document context found. You may answer based on " +
                    "previous conversation context if relevant, otherwise politely " +
                    "inform the user this is outside scope of available documents.";
        }
        return "Context from documents:" + System.lineSeparator() +
                buildContext(docs) + System.lineSeparator() + System.lineSeparator() +
                "Question: " + message;
    }


    // ---------------------------------------------------------------
    // chat() — configurable retrieval strategy + configurable memory
    // ---------------------------------------------------------------

    public Mono<ChatResponse> chat(String message, String conversationId,
                                   Boolean memoryEnabled, UUID tenantId,
                                   TenantContext tenantContext) {
        long start = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
                    // ONLY blocking work here
                    List<Document> relevantDocs = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(message)
                                    .topK(properties.getTopK())
                                    .similarityThreshold(properties.getSimilarityThreshold())
                                    .filterExpression("tenant_id == '" + tenantId + "'")
                                    .build()
                    );

                    String augmentedMessage = buildAugmentedMessage(message, relevantDocs);

                    boolean useMemory = memoryEnabled != null
                            ? memoryEnabled
                            : chatMemoryProperties.isEnabledByDefault();

                    return new Object[]{augmentedMessage, useMemory};
                })
                .subscribeOn(Schedulers.boundedElastic()) // blocking isolated
                .flatMap(data -> {
                    String augmentedMessage = (String) data[0];
                    boolean useMemory = (boolean) data[1];

                    // PURE reactive from here
                    return providerRouter.executeChat(
                            tenantContext,
                            augmentedMessage,
                            useMemory,
                            conversationId
                    );
                })
                .map(res -> {
                    long latency = System.currentTimeMillis() - start;

                    return new ChatResponse(
                            res.content(),
                            res.model(),
                            res.promptTokens(),
                            res.completionTokens(),
                            res.totalTokens(),
                            latency
                    );
                })
                .flatMap(response -> auditService.recordQuery(
                        tenantContext,
                        message,
                        "vector-only",
                        List.of(),
                        response.promptTokens(),
                        response.completionTokens(),
                        response.totalTokens(),
                        response.latencyMs()
                ).thenReturn(response)
                )
                .onErrorResume(ex -> {
                    log.warn("Fallback triggered message='{}' reason={}", message, ex.getMessage());
                    return Mono.just(new ChatResponse(
                            "Service temporarily unavailable. Please try again shortly.",
                            "fallback", 0, 0, 0, 0L
                    ));
                });
    }
    // ---------------------------------------------------------------
    // stream() — configurable retrieval strategy, no memory
    // ---------------------------------------------------------------

    public Flux<String> stream(String message, String retrievalStrategy,
                               UUID tenantId, TenantContext tenantContext) {

        long start = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
                    // ONLY blocking work
                    List<Document> relevantDocs = retrieve(message, retrievalStrategy, tenantId);

                    log.debug("Streaming retrieved {} chunks strategy='{}' query='{}'",
                            relevantDocs.size(), retrievalStrategy, message);

                    return buildAugmentedMessage(message, relevantDocs);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(augmentedMessage ->
                        //  PURE reactive — use router
                        providerRouter.executeStream(
                                tenantContext,
                                augmentedMessage
                        )
                )
                .concatWith(
                        auditService.recordStream(
                                tenantContext,
                                message,
                                retrievalStrategy != null
                                        ? retrievalStrategy
                                        : retrievalProperties.getDefaultStrategy(),
                                System.currentTimeMillis() - start
                        ).thenMany(Flux.empty())
                )
                .onErrorResume(ex -> Flux.just(
                        "Service temporarily unavailable. Please try again shortly."
                ));
    }
    // ---------------------------------------------------------------
    // query() — configurable retrieval strategy + configurable memory
    // stream() always stateless — no memory, no change needed
    // ---------------------------------------------------------------

    public Mono<QueryResponse> query(String message, String retrievalStrategy,
                                     Boolean memoryEnabled, String conversationId,
                                     UUID tenantId, TenantContext tenantContext) {

        long start = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
                    List<Document> relevantDocs = retrieve(message, retrievalStrategy, tenantId);

                    List<QueryResponse.CitedSource> sources = relevantDocs.stream()
                            .map(doc -> new QueryResponse.CitedSource(
                                    (String) doc.getMetadata().getOrDefault(
                                            "original_file_name",
                                            doc.getMetadata().get("file_name")),
                                    doc.getMetadata().get("page_number") != null
                                            ? Integer.parseInt(String.valueOf(
                                            doc.getMetadata().get("page_number")))
                                            : 0,
                                    doc.getText().substring(0,
                                                    Math.min(200, doc.getText().length()))
                                            .trim()
                                            .replaceAll("\\s+", " "),
                                    doc.getText()
                            ))
                            .toList();

                    String augmentedMessage = buildAugmentedMessage(message, relevantDocs);

                    String resolvedStrategy = (retrievalStrategy != null
                            && !retrievalStrategy.isBlank())
                            ? retrievalStrategy
                            : retrievalProperties.getDefaultStrategy();

                    boolean useMemory = memoryEnabled != null
                            ? memoryEnabled
                            : chatMemoryProperties.isEnabledByDefault();

                    return new Object[]{augmentedMessage, sources, resolvedStrategy, useMemory};
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(data -> {

                    String augmentedMessage = (String) data[0];
                    List<QueryResponse.CitedSource> sources =
                            (List<QueryResponse.CitedSource>) data[1];
                    String resolvedStrategy = (String) data[2];
                    boolean useMemory = (boolean) data[3];

                    // Reactive LLM call via router
                    return providerRouter.executeChat(
                            tenantContext,
                            augmentedMessage,
                            useMemory,
                            conversationId
                    ).map(res -> {
                        long latency = System.currentTimeMillis() - start;

                        return new QueryResponse(
                                res.content(),
                                res.model(),
                                sources,
                                sources.isEmpty() ? 0.0 : 0.8,
                                UUID.randomUUID(),
                                resolvedStrategy,
                                res.promptTokens(),
                                res.completionTokens(),
                                res.totalTokens(),
                                latency
                        );
                    });
                })
                .flatMap(response -> auditService.recordQuery(
                        tenantContext,
                        message,
                        response.retrievalStrategy(),
                        response.sources().stream()
                                .map(QueryResponse.CitedSource::fileName)
                                .toList(),
                        response.promptTokens(),
                        response.completionTokens(),
                        response.totalTokens(),
                        response.latencyMs()
                ).thenReturn(response))
                .onErrorResume(ex -> Mono.just(new QueryResponse(
                        "Service temporarily unavailable. Please try again shortly.","NA",
                        List.of(), 0.0, UUID.randomUUID()
                )));
    }
}