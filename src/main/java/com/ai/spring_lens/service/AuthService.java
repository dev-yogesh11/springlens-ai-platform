package com.ai.spring_lens.service;

import com.ai.spring_lens.repository.UserRepository;
import com.ai.spring_lens.repository.UserRepository.UserRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Authentication service — validates login credentials and issues JWT.
 *
 * Flow:
 * 1. Load user by email from DB (blocking JDBC on boundedElastic)
 * 2. Check user is enabled
 * 3. BCrypt verify submitted password against stored hash
 * 4. If valid — generate JWT with userId, tenantId, role claims
 * 5. Return LoginResult with token and user metadata
 *
 * Never returns specific error reason to caller —
 * always "Invalid email or password" to prevent user enumeration attacks.
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = new BCryptPasswordEncoder(12);
    }

    /**
     * Authenticates user and returns JWT token on success.
     *
     * Runs on boundedElastic — DB lookup and BCrypt are both blocking.
     * BCrypt cost factor 12 adds ~300ms intentionally — slows brute force.
     *
     * @param email     submitted email
     * @param password  submitted plain password
     * @return LoginResult with JWT token or error Mono on failure
     */
    public Mono<LoginResult> login(String email, String password) {
        return Mono.fromCallable(() -> authenticate(email, password))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(result -> log.info(
                        "Login successful: email={} role={} tenantId={}",
                        result.email(), result.role(), result.tenantId()))
                .doOnError(ex -> log.warn(
                        "Login failed: email={} reason={}",
                        email, ex.getMessage()));
    }

    /**
     * Blocking authentication logic — runs on boundedElastic.
     * Throws IllegalArgumentException on any failure —
     * message is always generic to prevent user enumeration.
     */
    private LoginResult authenticate(String email, String password) {
        UserRecord user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid email or password"));

        if (!user.enabled()) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        String token = jwtService.generateToken(
                user.email(),
                user.id(),
                user.tenantId(),
                user.role()
        );

        return new LoginResult(
                token,
                user.email(),
                user.role(),
                user.tenantId(),
                user.id()
        );
    }

    /**
     * Result of a successful login.
     * Returned to AuthController and sent to client.
     * Never includes password hash.
     */
    public record LoginResult(
            String token,
            String email,
            String role,
            UUID tenantId,
            UUID userId
    ) {}
}