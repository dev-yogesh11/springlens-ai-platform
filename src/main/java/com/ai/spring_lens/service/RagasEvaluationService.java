package com.ai.spring_lens.service;

import com.ai.spring_lens.config.RagasProperties;
import com.ai.spring_lens.model.ragas.RagasEvaluationRequest;
import com.ai.spring_lens.model.ragas.RagasEvaluationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * WebClient-based service for calling the Python RAGAS evaluation service.
 *
 * Non-blocking — returns Mono<RagasEvaluationResponse>.
 * RAGAS evaluation can take 10-30 seconds depending on pair count and
 * LLM scoring latency — caller should expect slow response for large batches.
 *
 * If RAGAS service is unavailable (enabled: false or service down),
 * returns a graceful error response rather than crashing.
 *
 * Python RAGAS service: FastAPI on port 8088
 * Endpoint: POST /evaluate
 */
@Slf4j
@Service
public class RagasEvaluationService {

    private final WebClient webClient;
    private final RagasProperties properties;

    public RagasEvaluationService(WebClient.Builder webClientBuilder,
                                   RagasProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Submits evaluation pairs to RAGAS Python service and returns scores.
     *
     * Non-blocking — suitable for WebFlux controller.
     * RAGAS internally calls OpenAI GPT-4o-mini for scoring — latency
     * depends on pair count. Expect 5-30 seconds for 20 pairs.
     *
     * @param request evaluation request containing pairs and strategy name
     * @return RAGAS scores — faithfulness, answer_relevancy,
     *         context_precision, context_recall
     */
    public Mono<RagasEvaluationResponse> evaluate(RagasEvaluationRequest request) {
        if (!properties.isEnabled()) {
            log.warn("RAGAS evaluation disabled via springlens.ragas.enabled=false");
            return Mono.error(new IllegalStateException(
                    "RAGAS evaluation service is disabled. " +
                    "Set springlens.ragas.enabled=true to enable."));
        }

        log.info("Submitting RAGAS evaluation: strategy='{}' pairs={}",
                request.retrievalStrategy(), request.pairs().size());

        return webClient.post()
                .uri(properties.getEvaluatePath())
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() ||
                                  status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> new RuntimeException(
                                        "RAGAS service error status=" +
                                        clientResponse.statusCode() +
                                        " body=" + body))
                )
                .bodyToMono(RagasEvaluationResponse.class)
                .doOnSuccess(response -> log.info(
                        "RAGAS evaluation complete: strategy='{}' pairs={} " +
                        "faithfulness={} answerRelevancy={} " +
                        "contextPrecision={} contextRecall={}",
                        response.retrievalStrategy(),
                        response.pairCount(),
                        response.scores().faithfulness(),
                        response.scores().answerRelevancy(),
                        response.scores().contextPrecision(),
                        response.scores().contextRecall()
                ))
                .doOnError(ex -> log.error(
                        "RAGAS evaluation failed: strategy='{}' reason={}",
                        request.retrievalStrategy(), ex.getMessage()
                ));
    }

    /**
     * Health check — verifies Python RAGAS service is reachable.
     * Called on admin dashboard load to show service status.
     */
    public Mono<Boolean> isHealthy() {
        return webClient.get()
                .uri(properties.getHealthPath())
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> true)
                .onErrorReturn(false);
    }
}
