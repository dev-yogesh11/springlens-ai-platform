package com.ai.spring_lens.model.ragas;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response DTO from RAGAS evaluation service.
 * Matches exactly the contract returned by POST /evaluate on the
 * Python FastAPI RAGAS service.
 *
 * scores: aggregate scores across all pairs in the request
 * per_pair_scores: individual scores per question — useful for
 *   identifying which specific questions have low quality
 * evaluated_at: ISO timestamp from Python service
 */
public record RagasEvaluationResponse(
        @JsonProperty("retrieval_strategy") String retrievalStrategy,
        @JsonProperty("pair_count") int pairCount,
        RagasScores scores,
        @JsonProperty("per_pair_scores") List<PerPairScore> perPairScores,
        @JsonProperty("evaluated_at") String evaluatedAt
) {
    /**
     * Aggregate RAGAS scores across all evaluated pairs.
     * All scores range 0.0 to 1.0 — higher is better.
     *
     * faithfulness:        are all answer claims supported by retrieved chunks?
     * answer_relevancy:    does the answer address what was asked?
     * context_precision:   are the most relevant chunks ranked at the top?
     * context_recall:      did retrieval find all information needed to answer?
     */
    public record RagasScores(
            double faithfulness,
            @JsonProperty("answer_relevancy") double answerRelevancy,
            @JsonProperty("context_precision") double contextPrecision,
            @JsonProperty("context_recall") double contextRecall
    ) {}

    /**
     * Per-question scores — identifies weak spots in specific Q&A pairs.
     * Low faithfulness on a specific question = hallucination risk for that topic.
     * Low context_recall on a specific question = missing document in corpus.
     */
    public record PerPairScore(
            String question,
            double faithfulness,
            @JsonProperty("answer_relevancy") double answerRelevancy,
            @JsonProperty("context_precision") double contextPrecision,
            @JsonProperty("context_recall") double contextRecall
    ) {}
}
