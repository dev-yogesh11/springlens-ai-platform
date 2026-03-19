package com.ai.spring_lens.config;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository(
            JedisPooled jedisPooled,
            ChatMemoryProperties properties) {
        return RedisChatMemoryRepository.builder()
                .jedisClient(jedisPooled)
                .keyPrefix("springlens:chat:")
                .maxMessagesPerConversation(properties.getMaxMessages())
                .ttlSeconds(properties.getTtlSeconds())
                .build();
    }

    @Bean
    public JedisPooled jedisPooled(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        return new JedisPooled(host, port);
    }
}