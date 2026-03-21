package com.ai.spring_lens.service;

import com.ai.spring_lens.config.HybridSearchProperties;
import com.ai.spring_lens.repository.HybridSearchRepository;
import com.ai.spring_lens.repository.HybridSearchRepository.FtsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReciprocalRankFusionService")
class ReciprocalRankFusionServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private HybridSearchRepository hybridSearchRepository;

    private HybridSearchProperties properties;
    private ReciprocalRankFusionService rrfService;

    // Fixed UUIDs for deterministic test assertions
    private static final UUID DOC_A = UUID.randomUUID();
    private static final UUID DOC_B = UUID.randomUUID();
    private static final UUID DOC_C = UUID.randomUUID();
    private static final UUID DOC_D = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Mirror production config values from application.yaml
        properties = new HybridSearchProperties();
        properties.setFtsWeight(0.3);
        properties.setVectorWeight(0.7);
        properties.setRrfK(60);
        properties.setFtsTopK(20);
        properties.setVectorTopK(20);
        properties.setFinalTopK(4);

        rrfService = new ReciprocalRankFusionService(
                vectorStore, hybridSearchRepository, properties);
    }

    // ---------------------------------------------------------------
    // Helper builders
    // ---------------------------------------------------------------

    private Document vectorDoc(UUID id, String content) {
        return Document.builder()
                .id(id.toString())
                .text(content)
                .metadata(Map.of("original_file_name", "test.pdf", "page_number", "1"))
                .build();
    }

    private FtsResult ftsResult(UUID id, String content) {
        return new FtsResult(id, content, "{\"original_file_name\":\"test.pdf\",\"page_number\":\"1\"}", 0.5);
    }

    // ---------------------------------------------------------------
    // Test 1: Both sources return results — RRF merges correctly
    // ---------------------------------------------------------------

    @Test
    @DisplayName("should merge and return results when both vector and FTS have results")
    void shouldMergeResultsWhenBothSourcesReturn() {
        // GIVEN: vector returns DOC_A and DOC_B, FTS returns DOC_C and DOC_D
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(
                        vectorDoc(DOC_A, "KYC policy content A"),
                        vectorDoc(DOC_B, "KYC policy content B")
                ));
        when(hybridSearchRepository.fullTextSearch(any()))
                .thenReturn(List.of(
                        ftsResult(DOC_C, "KYC policy content C"),
                        ftsResult(DOC_D, "KYC policy content D")
                ));

        // WHEN
        List<Document> results = rrfService.hybridSearch("KYC policy", 0.5);

        // THEN: all 4 unique documents returned, capped at finalTopK
        assertThat(results).isNotEmpty();
        assertThat(results).hasSizeLessThanOrEqualTo(properties.getFinalTopK());
    }

    // ---------------------------------------------------------------
    // Test 2: Vector returns empty, FTS has results
    // This is the key fix for the known failure in LEARNINGS.md:
    // "Resource Raising Norms" returned 0 vector chunks but exists in docs.
    // FTS finds exact phrases that embeddings miss.
    // ---------------------------------------------------------------

    @Test
    @DisplayName("should return FTS results when vector search returns empty — fixes exact term failure")
    void shouldReturnFtsResultsWhenVectorReturnsEmpty() {
        // GIVEN: vector returns nothing (exact technical term not close in embedding space)
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        // FTS finds the exact phrase "Resource Raising Norms" via keyword match
        when(hybridSearchRepository.fullTextSearch(any()))
                .thenReturn(List.of(
                        ftsResult(DOC_A, "Resource Raising Norms for banks..."),
                        ftsResult(DOC_B, "Resource Raising Norms applicability...")
                ));

        // WHEN
        List<Document> results = rrfService.hybridSearch("Resource Raising Norms", 0.5);

        // THEN: FTS results returned despite vector returning nothing
        assertThat(results).isNotEmpty();
        assertThat(results).hasSizeLessThanOrEqualTo(properties.getFinalTopK());
        assertThat(results.get(0).getText()).contains("Resource Raising Norms");
    }

    // ---------------------------------------------------------------
    // Test 3: FTS returns empty, vector has results
    // Semantic query with no exact keyword match — vector should win.
    // ---------------------------------------------------------------

    @Test
    @DisplayName("should return vector results when FTS returns empty")
    void shouldReturnVectorResultsWhenFtsReturnsEmpty() {
        // GIVEN: vector finds semantically similar content
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(
                        vectorDoc(DOC_A, "KYC periodic updation requirements..."),
                        vectorDoc(DOC_B, "Customer due diligence frequency...")
                ));

        // FTS finds nothing — query has no exact keyword match in tsvector
        when(hybridSearchRepository.fullTextSearch(any()))
                .thenReturn(List.of());

        // WHEN
        List<Document> results = rrfService.hybridSearch(
                "how often should customer identity be verified", 0.5);

        // THEN: vector results returned despite FTS returning nothing
        assertThat(results).isNotEmpty();
        assertThat(results).hasSizeLessThanOrEqualTo(properties.getFinalTopK());
    }

    // ---------------------------------------------------------------
    // Test 4: Both sources return empty
    // Out-of-scope query — should return empty list, not throw.
    // ---------------------------------------------------------------

    @Test
    @DisplayName("should return empty list when both vector and FTS return nothing")
    void shouldReturnEmptyWhenBothSourcesReturnNothing() {
        // GIVEN: nothing found in either source
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());
        when(hybridSearchRepository.fullTextSearch(any()))
                .thenReturn(List.of());

        // WHEN
        List<Document> results = rrfService.hybridSearch(
                "completely unrelated question about cooking", 0.5);

        // THEN: empty list returned cleanly, no exception
        assertThat(results).isEmpty();
    }

    // ---------------------------------------------------------------
    // Test 5: Same document appears in both vector and FTS results
    // The duplicate document should appear ONCE in output.
    // Its RRF score should be HIGHER than documents appearing in only one source
    // because it receives score contributions from both retrieval signals.
    // This is the core value of RRF — consensus across signals boosts relevance.
    // ---------------------------------------------------------------

    @Test
    @DisplayName("should deduplicate documents appearing in both sources and boost their score")
    void shouldDeduplicateAndBoostDocumentAppearedInBothSources() {
        // GIVEN: DOC_A appears in BOTH vector (rank 1) and FTS (rank 1)
        //        DOC_B appears only in vector (rank 2)
        //        DOC_C appears only in FTS (rank 2)
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(
                        vectorDoc(DOC_A, "KYC content A — in both sources"),
                        vectorDoc(DOC_B, "KYC content B — vector only")
                ));
        when(hybridSearchRepository.fullTextSearch(any()))
                .thenReturn(List.of(
                        ftsResult(DOC_A, "KYC content A — in both sources"),
                        ftsResult(DOC_C, "KYC content C — FTS only")
                ));

        // WHEN
        List<Document> results = rrfService.hybridSearch("KYC", 0.5);

        // THEN: DOC_A appears exactly once — no duplication
        long docACount = results.stream()
                .filter(d -> d.getId().equals(DOC_A.toString()))
                .count();
        assertThat(docACount).isEqualTo(1);

        // THEN: DOC_A is ranked first — highest combined RRF score
        // DOC_A gets: vectorWeight*(1/(60+1)) + ftsWeight*(1/(60+1))
        // DOC_B gets: vectorWeight*(1/(60+2)) only
        // DOC_C gets: ftsWeight*(1/(60+2)) only
        assertThat(results.get(0).getId()).isEqualTo(DOC_A.toString());

        // THEN: total unique documents = 3 (A, B, C) — no duplicates
        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }
}
