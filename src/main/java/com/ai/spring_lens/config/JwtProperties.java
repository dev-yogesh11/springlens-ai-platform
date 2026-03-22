package com.ai.spring_lens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT configuration properties.
 *
 * secret      — HMAC-SHA256 signing key, injected from JWT_SECRET env var
 * expirySeconds — token validity period (default 1 hour)
 * issuer      — JWT iss claim value — identifies this service as token issuer
 *
 * Secret must be at least 32 characters for HMAC-SHA256.
 * Loaded from environment — never hardcoded or committed to source control.
 */
@Data
@Component
@ConfigurationProperties(prefix = "springlens.jwt")
public class JwtProperties {
    private String secret;
    private long expirySeconds = 3600;
    private String issuer = "springlens";
}