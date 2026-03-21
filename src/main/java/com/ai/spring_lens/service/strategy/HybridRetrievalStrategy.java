package com.ai.spring_lens.service.strategy;

import com.ai.spring_lens.config.IngestionProperties;
import com.ai.spring_lens.service.ReciprocalRankFusionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hybrid search — vector similarity + PostgreSQL full-text search
 * merged via Reciprocal Rank Fusion. Phase 2 Week 11 retrieval logic.
 *
 * Bean name "hybrid" matches ChatRequest.retrievalStrategy value
 * and springlens.retrieval.default-strategy config value.
 *
 * Improvement over vector-only: finds exact technical terms and
 * regulatory phrases that embeddings miss. 49% token reduction
 * observed on KYC query vs pure vector. Fixes "Resource Raising
 * Norms" 0-chunk failure documented in Week 11 LEARNINGS.md.
 */
@Slf4j
@Component("hybrid")
public class HybridRetrievalStrategy implements RetrievalStrategy {

    private final ReciprocalRankFusionService rrfService;
    private final IngestionProperties properties;

    public HybridRetrievalStrategy(ReciprocalRankFusionService rrfService,
                                   IngestionProperties properties) {
        this.rrfService = rrfService;
        this.properties = properties;
    }

    @Override
    public List<Document> retrieve(String query, double similarityThreshold) {
        log.debug("Hybrid retrieval for query='{}'", query);

        List<Document> results = rrfService.hybridSearch(
                query, similarityThreshold
        );

        log.debug("Hybrid retrieved {} chunks for query='{}'",
                results.size(), query);
        return results;
    }
}
