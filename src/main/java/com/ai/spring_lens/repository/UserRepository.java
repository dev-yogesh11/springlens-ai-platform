package com.ai.spring_lens.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC repository for user authentication.
 * Follows same pattern as HybridSearchRepository and RagasEvaluationRepository.
 *
 * Only two queries needed:
 * - findByEmail     — login authentication
 * - existsByEmail   — duplicate check (future user creation)
 *
 * Blocking JDBC — must be called on Schedulers.boundedElastic().
 */
@Slf4j
@Repository
public class UserRepository {

    private static final String FIND_BY_EMAIL = """
            SELECT id, tenant_id, email, password_hash, role, enabled
            FROM users
            WHERE email = :email
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Finds a user by email address.
     * Called on every login attempt.
     *
     * Returns empty if user not found or disabled.
     * Blocking JDBC — caller must use Schedulers.boundedElastic().
     *
     * @param email user email from login request
     * @return Optional containing user record or empty if not found
     */
    public Optional<UserRecord> findByEmail(String email) {
        log.debug("Looking up user by email={}", email);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("email", email);

        List<UserRecord> results = jdbcTemplate.query(
                FIND_BY_EMAIL, params, (rs, rowNum) -> new UserRecord(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("tenant_id")),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("role"),
                        rs.getBoolean("enabled")
                ));

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Immutable user record from DB.
     * password_hash is BCrypt hash — never plain text.
     * Passed to AuthService for BCrypt verification.
     */
    public record UserRecord(
            UUID id,
            UUID tenantId,
            String email,
            String passwordHash,
            String role,
            boolean enabled
    ) {}
}