package com.ai.spring_lens.security;

import com.ai.spring_lens.service.JwtService;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Reactive JWT authentication filter.
 *
 * Runs on every request before controllers.
 * Extracts JWT from Authorization header, validates it,
 * and populates both Spring Security context and TenantContext.
 *
 * Java analogy: like OncePerRequestFilter in Spring MVC servlet stack
 * but implements WebFilter for reactive WebFlux.
 *
 * Flow:
 * 1. Extract Bearer token from Authorization header
 * 2. Validate JWT signature and expiry
 * 3. Extract claims — userId, tenantId, role, email
 * 4. Set Spring Security authentication context
 * 5. Store TenantContext in Reactor Context for downstream use
 * 6. Pass request to next filter in chain
 *
 * If no token or invalid token — request continues unauthenticated.
 * SecurityConfig decides which endpoints require authentication.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             WebFilterChain chain) {
        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        // No Authorization header — pass through unauthenticated
        // SecurityConfig will reject if endpoint requires auth
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        Claims claims = jwtService.validateAndExtract(token);

        // Invalid or expired token
        if (claims == null) {
            log.debug("Invalid or expired JWT token");
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // Extract claims
        String email = jwtService.extractEmail(claims);
        UUID userId = jwtService.extractUserId(claims);
        UUID tenantId = jwtService.extractTenantId(claims);
        String role = jwtService.extractRole(claims);

        // Build Spring Security authentication
        // ROLE_ prefix required by Spring Security for hasRole() checks
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );

        // Build TenantContext for downstream services
        TenantContext tenantContext = new TenantContext(
                userId, tenantId, email, role);

        log.debug("JWT authenticated: email={} role={} tenantId={}",
                email, role, tenantId);

        // Chain both Spring Security context and TenantContext
        // into Reactor Context for downstream access
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder
                        .withAuthentication(authentication))
                .contextWrite(ctx -> ctx.put(
                        TenantContext.CONTEXT_KEY, tenantContext));
    }
}