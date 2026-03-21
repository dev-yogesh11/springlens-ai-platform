package com.ai.spring_lens.service.strategy;

import com.ai.spring_lens.config.IngestionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pure vector similarity search — Phase 1 retrieval logic.
 * Preserved as baseline for demo comparison and regression testing.
 *
 * Bean name "vector-only" matches ChatRequest.retrievalStrategy value
 * and springlens.retrieval.default-strategy config value.
 *
 * Use case: demonstrate retrieval quality before hybrid search was added.
 * Weaknesses: misses exact technical terms, acronyms, regulatory phrases
 * that are not close in embedding space.
 */
@Slf4j
@Component("vector-only")
public class VectorOnlyRetrievalStrategy implements RetrievalStrategy {

    private final VectorStore vectorStore;
    private final IngestionProperties properties;

    public VectorOnlyRetrievalStrategy(VectorStore vectorStore,
                                       IngestionProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @Override
    public List<Document> retrieve(String query, double similarityThreshold) {
        log.debug("VectorOnly retrieval for query='{}'", query);

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(properties.getTopK())
                        .similarityThreshold(similarityThreshold)
                        .build()
        );

        log.debug("VectorOnly retrieved {} chunks for query='{}'",
                results.size(), query);
        return results;
    }
}
