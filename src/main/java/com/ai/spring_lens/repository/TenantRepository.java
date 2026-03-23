package com.ai.spring_lens.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC repository for tenant data.
 * Follows same pattern as UserRepository and HybridSearchRepository.
 *
 * Used by BudgetEnforcementService to load tenant budget limits
 * on every request for budget checking.
 *
 * Blocking JDBC — must be called on Schedulers.boundedElastic().
 */
@Slf4j
@Repository
public class TenantRepository {

    private static final String FIND_BY_ID = """
            SELECT id, name, enabled,
                   monthly_token_budget, daily_token_budget, daily_request_limit
            FROM tenants
            WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TenantRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Finds a tenant by UUID.
     * Called on every request for budget enforcement.
     *
     * Returns empty if tenant not found or disabled.
     * Blocking JDBC — caller must use Schedulers.boundedElastic().
     *
     * @param tenantId tenant UUID from JWT claim
     * @return Optional containing tenant record or empty if not found
     */
    public Optional<TenantRecord> findById(UUID tenantId) {
        log.debug("Looking up tenant by id={}", tenantId);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", tenantId);

        List<TenantRecord> results = jdbcTemplate.query(
                FIND_BY_ID, params, (rs, rowNum) -> new TenantRecord(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("name"),
                        rs.getBoolean("enabled"),
                        rs.getObject("monthly_token_budget") != null
                                ? rs.getLong("monthly_token_budget") : null,
                        rs.getObject("daily_token_budget") != null
                                ? rs.getLong("daily_token_budget") : null,
                        rs.getObject("daily_request_limit") != null
                                ? rs.getInt("daily_request_limit") : null
                ));

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Immutable tenant record from DB.
     * Budget fields are nullable — null means unlimited.
     */
    public record TenantRecord(
            UUID id,
            String name,
            boolean enabled,
            Long monthlyTokenBudget,
            Long dailyTokenBudget,
            Integer dailyRequestLimit
    ) {}
}