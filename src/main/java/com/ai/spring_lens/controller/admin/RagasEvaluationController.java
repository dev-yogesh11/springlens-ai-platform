package com.ai.spring_lens.controller.admin;

import com.ai.spring_lens.model.ragas.RagasEvaluationRequest;
import com.ai.spring_lens.service.RagasEvaluationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Admin endpoint for RAGAS quality evaluation.
 * Not a public API — internal quality measurement and monitoring only.
 *
 * POST /api/v1/admin/evaluate — run RAGAS evaluation on provided pairs
 * GET  /api/v1/admin/evaluate/health — check RAGAS service availability
 *
 * Separated from public controller package intentionally —
 * admin endpoints will have JWT ADMIN role restriction in Phase 2 Week 13.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/evaluate")
public class RagasEvaluationController {

    private final RagasEvaluationService ragasEvaluationService;

    public RagasEvaluationController(RagasEvaluationService ragasEvaluationService) {
        this.ragasEvaluationService = ragasEvaluationService;
    }

    /**
     * Run RAGAS evaluation on provided Q&A pairs.
     *
     * Accepts list of evaluation pairs with question, ground_truth,
     * answer from SpringLens, and contexts (fullText from sources).
     *
     * Returns aggregate scores + per-pair breakdown.
     * Expect 5-30 seconds response time for 20 pairs.
     *
     * Example:
     * POST /api/v1/admin/evaluate
     * {
     *   "pairs": [...],
     *   "retrieval_strategy": "hybrid-rerank"
     * }
     */
    @PostMapping
    public Mono<ResponseEntity<Object>> evaluate(
            @RequestBody RagasEvaluationRequest request
    ) {
        log.info("RAGAS evaluation requested: strategy='{}' pairs={}",
                request.retrievalStrategy(), request.pairs().size());

        return ragasEvaluationService.evaluate(request)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(ex -> {
                    log.error("RAGAS evaluation endpoint error: {}", ex.getMessage());
                    return Mono.just(ResponseEntity
                            .internalServerError()
                            .body((Object) ("RAGAS evaluation failed: " +
                                    ex.getMessage())));
                });
    }

    /**
     * Health check for RAGAS Python service.
     * Returns 200 if service is reachable, 503 if not.
     * Use this before submitting evaluation pairs to avoid timeout.
     */
    @GetMapping("/health")
    public Mono<ResponseEntity<Object>> health() {
        return ragasEvaluationService.isHealthy()
                .map(healthy -> healthy
                        ? ResponseEntity.ok()
                                .<Object>body("{\"status\":\"ok\"," +
                                        "\"ragas_service\":\"reachable\"}")
                        : ResponseEntity.status(503)
                                .<Object>body("{\"status\":\"degraded\"," +
                                        "\"ragas_service\":\"unreachable\"}"));
    }
}
