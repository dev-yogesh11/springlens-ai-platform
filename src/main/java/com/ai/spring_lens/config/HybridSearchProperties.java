package com.ai.spring_lens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "springlens.hybrid-search")
public class HybridSearchProperties {
    private double ftsWeight = 0.3;
    private double vectorWeight = 0.7;
    private int rrfK = 60;
    private int ftsTopK = 20;
    private int vectorTopK = 20;
    private int finalTopK = 4;
}