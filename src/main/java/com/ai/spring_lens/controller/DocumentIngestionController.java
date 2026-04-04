package com.ai.spring_lens.controller;

import com.ai.spring_lens.security.TenantContext;
import com.ai.spring_lens.service.DocumentIngestionService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentIngestionController {

    private final DocumentIngestionService ingestionService;

    public DocumentIngestionController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Reads TenantContext from Reactor Context.
     * Populated by JwtAuthenticationFilter on every authenticated request.
     * Returns error Mono if context is missing — should never happen
     * since SecurityConfig requires authentication on all ingestion endpoints.
     */
    private Mono<TenantContext> tenantContext() {
        return Mono.deferContextual(ctx -> {
            TenantContext tenantContext =
                    ctx.getOrDefault(TenantContext.CONTEXT_KEY, null);

            if (tenantContext == null) {
                return Mono.error(new IllegalStateException(
                        "TenantContext missing from Reactor Context"));
            }
            return Mono.just(tenantContext);
        });
    }

    @PostMapping("/ingest")
    public Mono<ResponseEntity<Map<String, Object>>> ingest(
            @RequestPart("file") FilePart filePart
    ) {
        // Extract TenantContext first — same pattern as SpringAiChatController.
        // Without this, tenant_id is never propagated to the service and the
        // vector_store INSERT fails with a NOT NULL constraint violation.
        return tenantContext()
                .flatMap(tenantCtx ->
                        Mono.fromCallable(() ->
                                        Files.createTempFile("upload-", "-" + filePart.filename())
                                )
                                .flatMap(tempFile ->
                                        filePart.transferTo(tempFile)
                                                .then(Mono.fromCallable(() -> {

                                                    DocumentIngestionService.IngestionResult result =
                                                            ingestionService.ingest(
                                                                    new FileSystemResource(tempFile),
                                                                    filePart.filename(),
                                                                    tenantCtx          // ← pass context
                                                            );

                                                    Files.deleteIfExists(tempFile);

                                                    return ResponseEntity.<Map<String, Object>>ok(
                                                            Map.of(
                                                                    "filename", result.fileName(),
                                                                    "chunks", result.chunks(),
                                                                    "status", result.status()
                                                            ));
                                                })))
                                .subscribeOn(Schedulers.boundedElastic())
                )
                .onErrorResume(ex ->
                        Mono.just(
                                ResponseEntity.internalServerError()
                                        .body(Map.<String, Object>of(
                                                "error", "Ingestion failed",
                                                "message", ex.getMessage()
                                        ))));
    }
}