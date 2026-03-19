package com.ai.spring_lens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "springlens.chat.memory")
public class ChatMemoryProperties {
    private int maxMessages = 10;
    private long ttlSeconds = 3600;
    private String keyPrefix = "springlens:chat-";
}