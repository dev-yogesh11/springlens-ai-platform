package com.ai.spring_lens.service.strategy;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Strategy interface for document retrieval in the RAG pipeline.
 *
 * Three implementations available:
 *   "vector-only"     → VectorOnlyRetrievalStrategy
 *   "hybrid"          → HybridRetrievalStrategy
 *   "hybrid-rerank"   → HybridWithRerankRetrievalStrategy
 *
 * Spring auto-injects Map<String, RetrievalStrategy> — bean name
 * becomes map key. Adding a new strategy = one new @Component class,
 * zero changes to existing code. Open/Closed principle.
 *
 * All implementations must be safe to call on Schedulers.boundedElastic().
 * All underlying operations (JDBC, vector search, HTTP) are blocking.
 */
public interface RetrievalStrategy {

    /**
     * Retrieve relevant documents for the given query.
     *
     * @param query               natural language query from user
     * @param similarityThreshold minimum similarity score for vector results
     * @return ordered list of relevant documents, most relevant first
     */
    List<Document> retrieve(String query, double similarityThreshold);
}