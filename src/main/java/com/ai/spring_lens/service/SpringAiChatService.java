package com.ai.spring_lens.service;

import com.ai.spring_lens.config.IngestionProperties;
import com.ai.spring_lens.model.response.ChatResponse;
import com.ai.spring_lens.model.response.QueryResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SpringAiChatService {

    private static final String SYSTEM_PROMPT ="""
                        You are SpringLens, a helpful AI assistant.
                        Answer questions based on the provided context.
                        Always cite the source document and page number.
                        If the answer is not in the context, say so clearly.
                        """;
    private final ChatClient chatClient;
    private final CircuitBreaker circuitBreaker;
    private final VectorStore vectorStore;
    private final IngestionProperties properties;

    public SpringAiChatService(ChatClient.Builder builder,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               VectorStore vectorStore, IngestionProperties properties) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.circuitBreaker = circuitBreakerRegistry
                .circuitBreaker("groqClient");
        this.properties = properties;
    }

    public Mono<ChatResponse> chat(String message) {
        long start = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
                    // Step 1: retrieve relevant chunks from PGVector
                    List<Document> relevantDocs = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(message)
                                    .topK(properties.getTopK())
                                    .similarityThreshold(properties.getSimilarityThreshold())
                                    .build()
                    );

                    // Step 2: build context from retrieved chunks
                    String context = relevantDocs.stream()
                            .map(doc -> "Source: " + doc.getMetadata().getOrDefault("original_file_name",
                                    doc.getMetadata().get("file_name")) +
                                    " (Page " + doc.getMetadata().get("page_number") + ")" +
                                    System.lineSeparator() + "Content: " +
                                    doc.getText())
                            .collect(Collectors.joining(
                                    System.lineSeparator() + System.lineSeparator() +
                                            "---" + System.lineSeparator() + System.lineSeparator()
                            ));

                    // Step 3: build augmented prompt
                    String augmentedMessage = relevantDocs.isEmpty()
                            ? "The user asked: " + message +
                            "\n\nNo relevant information was found in the knowledge base. " +
                            "Politely inform the user that this question is outside the " +
                            "scope of the available documents."
                            : "Context from documents:" + System.lineSeparator() +
                            context + System.lineSeparator() + System.lineSeparator() +
                            "Question: " + message;

                    log.info("RAG retrieved {} chunks for query={}",
                            relevantDocs.size(), message);
                    log.debug("Augmented prompt sent to LLM:\n{}", augmentedMessage);

                    // Step 4: call LLM with augmented prompt
                    return chatClient.prompt()
                            .user(augmentedMessage)
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

                    log.debug(
                            "LLM response: model={} promptTokens={} completionTokens={} response=\n{}",
                            metadata != null ? metadata.getModel() : "unknown",
                            usage != null ? usage.getPromptTokens() : 0,
                            usage != null ? usage.getCompletionTokens() : 0,
                            text
                    );
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
                    log.warn("Fallback triggered message={} reason={}",
                            message, ex.getMessage());
                    return Mono.just(new ChatResponse(
                            "Service temporarily unavailable. Please try again shortly.",
                            "fallback", 0, 0, 0, 0L
                    ));
                });
    }

    public Flux<String> stream(String message) {
        return Mono.fromCallable(() -> {
                    // blocking similarity search — runs on boundedElastic
                    List<Document> relevantDocs = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(message)
                                    .topK(properties.getTopK())
                                    .similarityThreshold(properties.getSimilarityThreshold())
                                    .build()
                    );

                    String context = relevantDocs.stream()
                            .map(doc -> "Source: " + doc.getMetadata().getOrDefault(
                                    "original_file_name", doc.getMetadata().get("file_name")) +
                                    " (Page " + doc.getMetadata().get("page_number") + ")" +
                                    System.lineSeparator() + "Content: " + doc.getText())
                            .collect(Collectors.joining(
                                    System.lineSeparator() + System.lineSeparator() +
                                            "---" + System.lineSeparator() + System.lineSeparator()
                            ));

                    log.debug("Streaming RAG retrieved {} chunks for query={}",
                            relevantDocs.size(), message);

                    return relevantDocs.isEmpty()
                            ? "The user asked: " + message +
                            "\n\nNo relevant information found. Politely inform the user."
                            : "Context from documents:" + System.lineSeparator() +
                            context + System.lineSeparator() + System.lineSeparator() +
                            "Question: " + message;
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

    public Mono<QueryResponse> query(String message) {

        return Mono.fromCallable(() -> {
                    List<Document> relevantDocs = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(message)
                                    .topK(properties.getTopK())
                                    .similarityThreshold(properties.getSimilarityThreshold())
                                    .build()
                    );

                    List<QueryResponse.CitedSource> sources = relevantDocs.stream()
                            .map(doc -> new QueryResponse.CitedSource(
                                    (String) doc.getMetadata().getOrDefault(
                                            "original_file_name",
                                            doc.getMetadata().get("file_name")),
                                    Integer.parseInt(String.valueOf(
                                            doc.getMetadata().get("page_number"))),
                                    doc.getText().substring(0, Math.min(200, doc.getText().length()))
                                            .trim()
                                            .replaceAll("\\s+", " ")
                            ))
                            .toList();

                    String context = relevantDocs.stream()
                            .map(doc -> "Source: " + doc.getMetadata().getOrDefault(
                                    "original_file_name", doc.getMetadata().get("file_name")) +
                                    " (Page " + doc.getMetadata().get("page_number") + ")" +
                                    System.lineSeparator() + "Content: " + doc.getText())
                            .collect(Collectors.joining(
                                    System.lineSeparator() + System.lineSeparator() +
                                            "---" + System.lineSeparator() + System.lineSeparator()
                            ));

                    String augmentedMessage = relevantDocs.isEmpty()
                            ? "The user asked: " + message +
                            "\n\nNo relevant information found. Politely inform the user."
                            : "Context from documents:" + System.lineSeparator() +
                            context + System.lineSeparator() + System.lineSeparator() +
                            "Question: " + message;

                    log.debug("Query RAG retrieved {} chunks for query={}",
                            relevantDocs.size(), message);

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