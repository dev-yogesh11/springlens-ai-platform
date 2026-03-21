package com.ai.spring_lens.model.ragas;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request DTO for RAGAS evaluation service.
 * Matches exactly the contract accepted by POST /evaluate on the
 * Python FastAPI RAGAS service running on port 8088.
 *
 * Each EvaluationPair contains:
 * - question: the user query sent to SpringLens
 * - ground_truth: manually written correct answer
 * - answer: what SpringLens actually returned
 * - contexts: full chunk texts from sources[].fullText in QueryResponse
 *
 * retrieval_strategy: which strategy produced these results —
 * allows per-strategy score comparison in quality dashboard.
 */
public record RagasEvaluationRequest(
        List<EvaluationPair> pairs,
        @JsonProperty("retrieval_strategy") String retrievalStrategy
) {
    public record EvaluationPair(
            String question,
            @JsonProperty("ground_truth") String groundTruth,
            String answer,
            List<String> contexts
    ) {}
}
