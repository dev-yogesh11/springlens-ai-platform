package com.ai.spring_lens.model.response;

import java.util.List;
import java.util.UUID;

public record QueryResponse(
        String answer,
        List<CitedSource> sources,
        Double confidence,
        UUID queryId
) {
    public record CitedSource(
            String fileName,
            Integer pageNumber,
            String excerpt
    ) {}
}