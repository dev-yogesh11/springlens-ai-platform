package com.ai.spring_lens.service;

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

    public SpringAiChatService(ChatClient.Builder builder,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               VectorStore vectorStore,
                               IngestionProperties properties,
                               ChatMemoryRepository chatMemoryRepository,
                               Map<String, RetrievalStrategy> retrievalStrategies,
                               RetrievalProperties retrievalProperties) {
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
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
        this.circuitBreaker = circuitBreakerRegistry
                .circuitBreaker("groqClient");
        log.info("ChatMemoryRepository implementation: {}",
                chatMemoryRepository.getClass().getName());
        log.info("Available retrieval strategies: {}",
                retrievalStrategies.keySet());
    }

    // ---------------------------------------------------------------
    // Strategy resolution — request param overrides config default
    // Falls back to "hybrid" if unknown strategy name provided
    // ---------------------------------------------------------------

    private List<Document> retrieve(String query, String requestStrategy) {
        String strategyName = (requestStrategy != null && !requestStrategy.isBlank())
                ? requestStrategy
                : retrievalProperties.getDefaultStrategy();

        RetrievalStrategy strategy = retrievalStrategies.get(strategyName);

        if (strategy == null) {
            log.warn("Unknown retrieval strategy='{}', falling back to 'hybrid'",
                    strategyName);
            strategy = retrievalStrategies.get("hybrid");
        }

        log.info("Retrieval strategy='{}' query='{}'", strategyName, query);
        return strategy.retrieve(query, properties.getSimilarityThreshold());
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
    // chat() — pure vector baseline, conversation memory active
    // Retrieval strategy: always vector-only (baseline comparison)
    // ---------------------------------------------------------------

    public Mono<ChatResponse> chat(String message, String conversationId) {
        long start = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
                    // chat() always uses vector-only — preserved as Phase 1 baseline
                    List<Document> relevantDocs = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(message)
                                    .topK(properties.getTopK())
                                    .similarityThreshold(properties.getSimilarityThreshold())
                                    .build()
                    );

                    String augmentedMessage = buildAugmentedMessage(message, relevantDocs);

                    log.info("RAG retrieved {} chunks strategy=vector-only query='{}'",
                            relevantDocs.size(), message);
                    log.debug("Augmented prompt sent to LLM:\n{}", augmentedMessage);

                    return chatClient.prompt()
                            .user(augmentedMessage)
                            .advisors(advisor -> advisor
                                    .param(ChatMemory.CONVERSATION_ID,
                                            conversationId != null ? conversationId : "default"))
                            .call()
                            .chatResponse();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .doOnSuccess(response -> {
                    if (response == null) return;
                    var metadata = response.getMetadata();
                    var usage = metadata != null ? metadata.getUsage() : null;
                    var text = response.getResult() != null
                            ? response.getResult().getOutput().getText()
                            : "no content";
                    log.debug("LLM response: model={} promptTokens={} completionTokens={} response=\n{}",
                            metadata != null ? metadata.getModel() : "unknown",
                            usage != null ? usage.getPromptTokens() : 0,
                            usage != null ? usage.getCompletionTokens() : 0,
                            text);
                })
                .map(response -> {
                    var result = response.getResult();
                    var metadata = response.getMetadata();
                    var usage = metadata != null ? metadata.getUsage() : null;
                    return new ChatResponse(
                            result != null ? result.getOutput().getText() : "",
                            metadata != null ? metadata.getModel() : "unknown",
                            usage != null ? usage.getPromptTokens() : 0,
                            usage != null ? usage.getCompletionTokens() : 0,
                            usage != null ? usage.getTotalTokens() : 0,
                            System.currentTimeMillis() - start
                    );
                })
                .onErrorResume(ex -> {
                    log.warn("Fallback triggered message='{}' reason={}",
                            message, ex.getMessage());
                    return Mono.just(new ChatResponse(
                            "Service temporarily unavailable. Please try again shortly.",
                            "fallback", 0, 0, 0, 0L
                    ));
                });
    }

    // ---------------------------------------------------------------
    // stream() — configurable retrieval strategy, no memory
    // ---------------------------------------------------------------

    public Flux<String> stream(String message, String retrievalStrategy) {
        return Mono.fromCallable(() -> {
                    List<Document> relevantDocs = retrieve(message, retrievalStrategy);

                    log.debug("Streaming retrieved {} chunks strategy='{}' query='{}'",
                            relevantDocs.size(), retrievalStrategy, message);

                    return buildAugmentedMessage(message, relevantDocs);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(augmentedMessage -> chatClient.prompt()
                        .user(augmentedMessage)
                        .stream()
                        .content()
                )
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(ex -> Flux.just(
                        "Service temporarily unavailable. Please try again shortly."
                ));
    }

    // ---------------------------------------------------------------
    // query() — configurable retrieval strategy, no memory
    // Primary production endpoint — returns structured QueryResponse
    // ---------------------------------------------------------------

    public Mono<QueryResponse> query(String message, String retrievalStrategy) {
        return Mono.fromCallable(() -> {
                    List<Document> relevantDocs = retrieve(message, retrievalStrategy);

                    List<QueryResponse.CitedSource> sources = relevantDocs.stream()
                            .map(doc -> new QueryResponse.CitedSource(
                                    (String) doc.getMetadata().getOrDefault(
                                            "original_file_name",
                                            doc.getMetadata().get("file_name")),
                                    doc.getMetadata().get("page_number") != null
                                            ? Integer.parseInt(String.valueOf(
                                            doc.getMetadata().get("page_number")))
                                            : 0,
                                    doc.getText().substring(0, Math.min(200, doc.getText().length()))
                                            .trim()
                                            .replaceAll("\\s+", " ")
                            ))
                            .toList();

                    String augmentedMessage = buildAugmentedMessage(message, relevantDocs);

                    log.debug("Query retrieved {} chunks strategy='{}' query='{}'",
                            relevantDocs.size(), retrievalStrategy, message);

                    String answer = chatClient.prompt()
                            .user(augmentedMessage)
                            .call()
                            .content();

                    return new QueryResponse(
                            answer,
                            sources,
                            relevantDocs.isEmpty() ? 0.0 : 0.8,
                            UUID.randomUUID()
                    );
                })
                .subscribeOn(Schedulers.boundedElastic())
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(ex -> Mono.just(new QueryResponse(
                        "Service temporarily unavailable. Please try again shortly.",
                        List.of(), 0.0, UUID.randomUUID()
                )));
    }
}