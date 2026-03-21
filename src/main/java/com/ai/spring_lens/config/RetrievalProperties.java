package com.ai.spring_lens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "springlens.retrieval")
public class RetrievalProperties {
    private String defaultStrategy = "hybrid-rerank";
}
