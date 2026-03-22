package com.ai.spring_lens.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Spring Security configuration for WebFlux reactive stack.
 *
 * Java analogy: like WebSecurityConfigurerAdapter in Spring MVC
 * but uses ServerHttpSecurity for reactive WebFlux.
 *
 * Key decisions:
 * - CSRF disabled — REST API with JWT, no browser session
 * - No form login — JWT only
 * - No HTTP Basic — JWT only
 * - JwtAuthenticationFilter runs before Spring Security filters
 *
 * Role hierarchy:
 * - ADMIN  — full access to all endpoints
 * - USER   — can query, cannot ingest or view admin endpoints
 * - VIEWER — read only (future use)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http) {

        return http
                // Disable CSRF — REST API with JWT, no browser session
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // Disable form login — JWT only
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                // Disable HTTP Basic — JWT only
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

                // Endpoint authorization rules
                .authorizeExchange(exchanges -> exchanges

                        // Public endpoints — no token required
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/auth/login").permitAll()
                        .pathMatchers(
                                "/actuator/health",
                                "/actuator/info").permitAll()

                        // ADMIN only endpoints
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/documents/ingest").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/admin/evaluate").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/admin/quality").hasRole("ADMIN")
                        .pathMatchers(
                                "/api/v1/admin/**").hasRole("ADMIN")

                        // USER + ADMIN endpoints
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/ai/chat/query").hasAnyRole("USER", "ADMIN")
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/ai/chat").hasAnyRole("USER", "ADMIN")
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/ai/chat/stream").hasAnyRole("USER", "ADMIN")

                        // All other endpoints require authentication
                        .anyExchange().authenticated()
                )

                // Register JWT filter before Spring Security processes request
                .addFilterBefore(jwtAuthenticationFilter,
                        SecurityWebFiltersOrder.AUTHENTICATION)

                .build();
    }

    /**
     * Minimal ReactiveAuthenticationManager required by Spring Boot 4.0
     * to activate Security auto-configuration.
     *
     * JWT filter handles all authentication — this bean satisfies
     * the auto-configuration requirement only.
     */
    @Bean
    public ReactiveAuthenticationManager authenticationManager() {
        return authentication -> Mono.empty();
    }
}