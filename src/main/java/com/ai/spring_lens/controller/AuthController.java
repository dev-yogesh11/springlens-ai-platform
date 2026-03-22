package com.ai.spring_lens.controller;

import com.ai.spring_lens.service.AuthService;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Public authentication endpoint.
 * No JWT required — this is where tokens are obtained.
 *
 * POST /api/v1/auth/login — validates credentials, returns JWT
 *
 * Deliberately kept minimal — no registration, no password reset.
 * Users are created by admins directly in DB or via future admin API.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Authenticates user and returns JWT token.
     *
     * Returns 200 with token on success.
     * Returns 401 with generic message on any failure —
     * never reveals whether email exists or password is wrong.
     *
     * Example:
     * POST /api/v1/auth/login
     * {"email": "admin@springlens.com", "password": "Admin@123"}
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<Object>> login(
            @RequestBody LoginRequest request
    ) {
        return authService.login(request.email(), request.password())
                .<ResponseEntity<Object>>map(result ->
                        ResponseEntity.ok(new LoginResponse(
                                result.token(),
                                result.email(),
                                result.role(),
                                result.tenantId(),
                                3600
                        )))
                .onErrorResume(ex -> {
                    log.warn("Login endpoint error: {}", ex.getMessage());
                    return Mono.just(ResponseEntity
                            .status(401)
                            .body((Object) new ErrorResponse(
                                    "INVALID_CREDENTIALS",
                                    "Invalid email or password"
                            )));
                });
    }

    /**
     * Login request — email and password in request body.
     * Password sent as plain text over HTTPS — industry standard.
     * BCrypt comparison happens server-side.
     */
    public record LoginRequest(
            String email,
            String password
    ) {}

    /**
     * Login response — JWT token + user metadata.
     * expiresIn is seconds until token expiry.
     * Never includes password hash or internal IDs beyond tenantId.
     */
    public record LoginResponse(
            String token,
            String email,
            String role,
            @JsonProperty("tenant_id") UUID tenantId,
            @JsonProperty("expires_in") int expiresIn
    ) {}

    /**
     * Error response for failed login.
     * Always generic — never reveals whether email exists.
     */
    public record ErrorResponse(
            String code,
            String message
    ) {}
}