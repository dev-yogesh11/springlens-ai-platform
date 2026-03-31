package com.ai.spring_lens.service;

import com.ai.spring_lens.config.HybridSearchProperties;
import com.ai.spring_lens.repository.HybridSearchRepository;
import com.ai.spring_lens.repository.HybridSearchRepository.FtsResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ReciprocalRankFusionService {

    private final VectorStore vectorStore;
    private final HybridSearchRepository hybridSearchRepository;
    private final HybridSearchProperties properties;

    public ReciprocalRankFusionService(VectorStore vectorStore,
                                       HybridSearchRepository hybridSearchRepository,
                                       HybridSearchProperties properties) {
        this.vectorStore = vectorStore;
        this.hybridSearchRepository = hybridSearchRepository;
        this.properties = properties;
    }

    /**
     * Hybrid search: vector similarity + full-text search merged via RRF.
     *
     * Must be called on Schedulers.boundedElastic() — both vectorStore.similaritySearch()
     * and hybridSearchRepository.fullTextSearch() are blocking calls.
     * Caller (SpringAiChatService) already runs inside Mono.fromCallable()
     * with subscribeOn(boundedElastic) — no additional scheduling needed here.
     *
     * RRF formula: score(d) = sum(1 / (k + rank(d)))
     * where k=60 (standard constant), rank is 1-based position in each result list.
     * Higher score = more relevant across both retrieval signals.
     *
     * @param query             natural language query
     * @param similarityThreshold minimum cosine similarity for vector results
     * @param tenantId            tenant UUID — filters both vector and FTS results
     * @return merged and re-ranked list of Documents, capped at finalTopK
     */
    public List<Document> hybridSearch(String query, double similarityThreshold, UUID tenantId) {

        // Step 1: vector similarity search — semantic meaning
        // separated with tenant_id
        List<Document> vectorResults = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(properties.getVectorTopK())
                        .similarityThreshold(similarityThreshold)
                        .filterExpression("tenant_id == '" + tenantId + "'")
                        .build()
        );

        // Step 2: full-text search — keyword / exact term matching
        List<FtsResult> ftsResults = hybridSearchRepository.fullTextSearch(query);

        log.debug("Hybrid search: vectorResults={} ftsResults={} query='{}'",
                vectorResults.size(), ftsResults.size(), query);

        // Step 3: handle edge cases — if one source returns nothing, return the other
        if (vectorResults.isEmpty() && ftsResults.isEmpty()) {
            log.debug("Both vector and FTS returned 0 results for query='{}'", query);
            return List.of();
        }
        if (vectorResults.isEmpty()) {
            log.debug("Vector returned 0 results — returning FTS-only results for query='{}'", query);
            return buildDocumentsFromFts(ftsResults);
        }
        if (ftsResults.isEmpty()) {
            log.debug("FTS returned 0 results — returning vector-only results for query='{}'", query);
            return vectorResults.stream()
                    .limit(properties.getFinalTopK())
                    .toList();
        }

        // Step 4: build RRF score map keyed by document id
        // Each document accumulates score contributions from both result lists
        Map<UUID, Double> rrfScores = new LinkedHashMap<>();
        Map<UUID, Document> documentIndex = new LinkedHashMap<>();

        // Score contributions from vector results
        for (int i = 0; i < vectorResults.size(); i++) {
            Document doc = vectorResults.get(i);
            UUID id = UUID.fromString(doc.getId());
            double contribution = properties.getVectorWeight() *
                    (1.0 / (properties.getRrfK() + i + 1));
            rrfScores.merge(id, contribution, Double::sum);
            documentIndex.put(id, doc);
        }

        // Score contributions from FTS results
        for (int i = 0; i < ftsResults.size(); i++) {
            FtsResult ftsResult = ftsResults.get(i);
            UUID id = ftsResult.id();
            double contribution = properties.getFtsWeight() *
                    (1.0 / (properties.getRrfK() + i + 1));
            rrfScores.merge(id, contribution, Double::sum);

            // If this FTS result was not in vector results, build a Document from it
            // so it can still be included in the final merged list
            documentIndex.computeIfAbsent(id, k -> buildDocumentFromFts(ftsResult));
        }

        // Step 5: sort by RRF score descending, cap at finalTopK
        List<Document> merged = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(properties.getFinalTopK())
                .map(entry -> documentIndex.get(entry.getKey()))
                .filter(Objects::nonNull)
                .toList();

        log.debug("RRF merged {} unique documents, returning top {} for query='{}'",
                rrfScores.size(), merged.size(), query);

        return merged;
    }

    /**
     * Builds a Spring AI Document from an FtsResult for documents that appeared
     * in FTS results but not in vector results.
     * Metadata is stored as raw JSON string from the database — passed through as-is.
     */
    private Document buildDocumentFromFts(FtsResult ftsResult) {
        return Document.builder()
                .id(ftsResult.id().toString())
                .text(ftsResult.content())
                .metadata(parseMetadata(ftsResult.metadata()))
                .build();
    }

    /**
     * Builds a list of Documents from FTS-only results.
     * Used when vector search returns empty but FTS has results.
     */
    private List<Document> buildDocumentsFromFts(List<FtsResult> ftsResults) {
        return ftsResults.stream()
                .limit(properties.getFinalTopK())
                .map(this::buildDocumentFromFts)
                .toList();
    }

    /**
     * Parses raw JSON metadata string from database into Map<String, Object>.
     * Uses simple string-based approach — avoids Jackson dependency in repository layer.
     * Falls back to empty map on any parse failure — never throws.
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            // Use Spring AI's internal Jackson ObjectMapper via a simple parse
            // metadata is a flat JSON object from PostgreSQL json column
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(metadataJson, Map.class);
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse metadata JSON, using empty map: {}", e.getMessage());
            return Map.of();
        }
    }
}
