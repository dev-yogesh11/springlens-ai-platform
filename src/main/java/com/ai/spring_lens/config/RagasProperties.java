package com.ai.spring_lens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "springlens.ragas")
public class RagasProperties {
    private String baseUrl = "http://localhost:8088";
    private String evaluatePath = "/evaluate";
    private String healthPath = "/health";
    private boolean enabled = true;
}
