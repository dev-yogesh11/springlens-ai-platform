package com.ai.spring_lens.service;

import com.ai.spring_lens.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT token generation and validation service.
 *
 * Uses HMAC-SHA256 (HS256) algorithm — symmetric key signing.
 * SpringLens both issues and validates tokens — no external JWKS needed.
 *
 * Token claims:
 * - sub      : user email
 * - userId   : UUID of user in DB
 * - tenantId : UUID of tenant — used for data isolation
 * - role     : ADMIN | USER | VIEWER
 * - iss      : springlens (configured via JwtProperties)
 * - iat      : issued at timestamp
 * - exp      : expiry timestamp
 */
@Slf4j
@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey secretKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        // Keys.hmacShaKeyFor requires minimum 32 bytes for HS256
        this.secretKey = Keys.hmacShaKeyFor(
                properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a signed JWT token for an authenticated user.
     *
     * @param email    user email — becomes JWT subject
     * @param userId   user UUID from DB
     * @param tenantId tenant UUID from DB
     * @param role     user role — ADMIN | USER | VIEWER
     * @return signed JWT string
     */
    public String generateToken(String email, UUID userId,
                                UUID tenantId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() +
                properties.getExpirySeconds() * 1000);

        return Jwts.builder()
                .subject(email)
                .claim("userId", userId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("role", role)
                .issuer(properties.getIssuer())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Validates a JWT token and extracts its claims.
     *
     * Returns null if token is invalid, expired, or tampered.
     * Never throws — caller checks for null.
     *
     * @param token raw JWT string from Authorization header
     * @return parsed Claims or null if invalid
     */
    public Claims validateAndExtract(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT validation failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Extracts email (subject) from a validated claims object.
     */
    public String extractEmail(Claims claims) {
        return claims.getSubject();
    }

    /**
     * Extracts tenantId claim from a validated claims object.
     */
    public UUID extractTenantId(Claims claims) {
        return UUID.fromString(claims.get("tenantId", String.class));
    }

    /**
     * Extracts userId claim from a validated claims object.
     */
    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.get("userId", String.class));
    }

    /**
     * Extracts role claim from a validated claims object.
     */
    public String extractRole(Claims claims) {
        return claims.get("role", String.class);
    }
}