package com.ai.spring_lens.model.response;

import java.util.List;
import java.util.UUID;

public record QueryResponse(
        String answer,
        List<CitedSource> sources,
        Double confidence,
        UUID queryId,
        String retrievalStrategy,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long latencyMs
) {
    // Backward-compatible constructor — used by error fallback paths
    public QueryResponse(String answer, List<CitedSource> sources,
                         Double confidence, UUID queryId) {
        this(answer, sources, confidence, queryId, "unknown", 0, 0, 0, 0L);
    }

    public record CitedSource(
            String fileName,
            Integer pageNumber,
            String excerpt,    // 200 char truncated — for UI display
            String fullText    // complete chunk text — for RAGAS evaluation
    ) {}
}