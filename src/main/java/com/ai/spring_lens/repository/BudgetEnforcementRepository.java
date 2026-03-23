package com.ai.spring_lens.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * JDBC repository for budget enforcement queries.
 * All queries run against audit_events table.
 *
 * Four queries — all follow same pattern:
 * COUNT or SUM from audit_events with time window filter.
 *
 * Blocking JDBC — must be called on Schedulers.boundedElastic().
 * Caller (BudgetEnforcementService) handles scheduling.
 */
@Slf4j
@Repository
public class BudgetEnforcementRepository {

    // ── Daily request count — for rate limiting ───────────────
    private static final String COUNT_USER_REQUESTS_TODAY = """
            SELECT COUNT(*)
            FROM audit_events
            WHERE user_id = :userId
            AND created_at >= :startOfDay
            """;

    private static final String COUNT_TENANT_REQUESTS_TODAY = """
            SELECT COUNT(*)
            FROM audit_events
            WHERE tenant_id = :tenantId
            AND created_at >= :startOfDay
            """;

    // ── Daily token sum — for daily budget ───────────────────
    private static final String SUM_USER_TOKENS_TODAY = """
            SELECT COALESCE(SUM(total_tokens), 0)
            FROM audit_events
            WHERE user_id = :userId
            AND created_at >= :startOfDay
            """;

    private static final String SUM_TENANT_TOKENS_TODAY = """
            SELECT COALESCE(SUM(total_tokens), 0)
            FROM audit_events
            WHERE tenant_id = :tenantId
            AND created_at >= :startOfDay
            """;

    // ── Monthly token sum — for monthly budget ────────────────
    private static final String SUM_USER_TOKENS_THIS_MONTH = """
            SELECT COALESCE(SUM(total_tokens), 0)
            FROM audit_events
            WHERE user_id = :userId
            AND created_at >= :startOfMonth
            """;

    private static final String SUM_TENANT_TOKENS_THIS_MONTH = """
            SELECT COALESCE(SUM(total_tokens), 0)
            FROM audit_events
            WHERE tenant_id = :tenantId
            AND created_at >= :startOfMonth
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public BudgetEnforcementRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Counts requests made by a user today.
     * Used for user daily request limit check.
     */
    public long countUserRequestsToday(UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("startOfDay", LocalDate.now().atStartOfDay());
        Long count = jdbcTemplate.queryForObject(
                COUNT_USER_REQUESTS_TODAY, params, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * Counts requests made by all users in a tenant today.
     * Used for tenant daily request limit check.
     */
    public long countTenantRequestsToday(UUID tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("startOfDay", LocalDate.now().atStartOfDay());
        Long count = jdbcTemplate.queryForObject(
                COUNT_TENANT_REQUESTS_TODAY, params, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * Sums total tokens consumed by a user today.
     * Used for user daily token budget check.
     */
    public long sumUserTokensToday(UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("startOfDay", LocalDate.now().atStartOfDay());
        Long sum = jdbcTemplate.queryForObject(
                SUM_USER_TOKENS_TODAY, params, Long.class);
        return sum != null ? sum : 0L;
    }

    /**
     * Sums total tokens consumed by all users in a tenant today.
     * Used for tenant daily token budget check.
     */
    public long sumTenantTokensToday(UUID tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("startOfDay", LocalDate.now().atStartOfDay());
        Long sum = jdbcTemplate.queryForObject(
                SUM_TENANT_TOKENS_TODAY, params, Long.class);
        return sum != null ? sum : 0L;
    }

    /**
     * Sums total tokens consumed by a user this calendar month.
     * Used for user monthly token budget check.
     */
    public long sumUserTokensThisMonth(UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("startOfMonth", YearMonth.now()
                        .atDay(1).atStartOfDay());
        Long sum = jdbcTemplate.queryForObject(
                SUM_USER_TOKENS_THIS_MONTH, params, Long.class);
        return sum != null ? sum : 0L;
    }

    /**
     * Sums total tokens consumed by all users in a tenant this month.
     * Used for tenant monthly token budget check.
     */
    public long sumTenantTokensThisMonth(UUID tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("startOfMonth", YearMonth.now()
                        .atDay(1).atStartOfDay());
        Long sum = jdbcTemplate.queryForObject(
                SUM_TENANT_TOKENS_THIS_MONTH, params, Long.class);
        return sum != null ? sum : 0L;
    }
}