package com.ai.spring_lens.service;

import com.ai.spring_lens.config.BudgetProperties;
import com.ai.spring_lens.repository.BudgetEnforcementRepository;
import com.ai.spring_lens.repository.TenantRepository;
import com.ai.spring_lens.repository.TenantRepository.TenantRecord;
import com.ai.spring_lens.repository.UserRepository;
import com.ai.spring_lens.repository.UserRepository.UserRecord;
import com.ai.spring_lens.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * Budget enforcement service — runs 6 checks on every request
 * before allowing it to proceed.
 *
 * Check order:
 * 1. User daily request limit   → 429 if exceeded
 * 2. Tenant daily request limit → 429 if exceeded
 * 3. User daily token budget    → 429 if exceeded
 * 4. Tenant daily token budget  → 429 if exceeded
 * 5. User monthly token budget  → 402 if exceeded
 * 6. Tenant monthly token budget→ 402 if exceeded
 *
 * NULL budget = unlimited — check is skipped.
 * Warning headers added to BudgetCheckResult when at 80%+ of any limit.
 *
 * Runs on Schedulers.boundedElastic() — all operations are blocking JDBC.
 */
@Slf4j
@Service
public class BudgetEnforcementService {

    private final BudgetEnforcementRepository budgetRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final BudgetProperties budgetProperties;

    public BudgetEnforcementService(
            BudgetEnforcementRepository budgetRepository,
            UserRepository userRepository,
            TenantRepository tenantRepository,
            BudgetProperties budgetProperties) {
        this.budgetRepository = budgetRepository;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.budgetProperties = budgetProperties;
    }

    /**
     * Result of a budget check — passed to controller for header injection.
     * warnings list contains header values to add to response.
     */
    public record BudgetCheckResult(List<String> warnings) {
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    /**
     * Runs all 6 budget checks for the given tenant context.
     *
     * Throws ResponseStatusException (429 or 402) if any limit exceeded.
     * Returns BudgetCheckResult with warnings if approaching any limit.
     *
     * Runs on boundedElastic — all JDBC calls are blocking.
     *
     * @param tenantContext tenant and user identity from JWT
     * @return BudgetCheckResult with any warning messages
     */
    public Mono<BudgetCheckResult> enforce(TenantContext tenantContext) {
        return Mono.fromCallable(() -> runAllChecks(tenantContext))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Blocking enforcement logic — runs on boundedElastic.
     * Loads user and tenant records, runs all 6 checks in order.
     */
    private BudgetCheckResult runAllChecks(TenantContext tenantContext) {
        // Load user and tenant records with budget limits
        UserRecord user = userRepository.findByEmail(tenantContext.email())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));

        TenantRecord tenant = tenantRepository.findById(tenantContext.tenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Tenant not found"));

        List<String> warnings = new ArrayList<>();

        // ── Check 1: User daily request limit ────────────────
        if (user.dailyRequestLimit() != null) {
            long userRequestsToday = budgetRepository
                    .countUserRequestsToday(tenantContext.userId());
            if (userRequestsToday >= user.dailyRequestLimit()) {
                log.warn("User daily request limit exceeded: userId={} count={} limit={}",
                        tenantContext.userId(), userRequestsToday,
                        user.dailyRequestLimit());
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Daily request limit exceeded. Resets at midnight.");
            }
            addWarningIfNeeded(warnings, "user-daily-requests",
                    userRequestsToday, user.dailyRequestLimit());
        }

        // ── Check 2: Tenant daily request limit ──────────────
        if (tenant.dailyRequestLimit() != null) {
            long tenantRequestsToday = budgetRepository
                    .countTenantRequestsToday(tenantContext.tenantId());
            if (tenantRequestsToday >= tenant.dailyRequestLimit()) {
                log.warn("Tenant daily request limit exceeded: tenantId={} count={} limit={}",
                        tenantContext.tenantId(), tenantRequestsToday,
                        tenant.dailyRequestLimit());
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Tenant daily request limit exceeded. Resets at midnight.");
            }
            addWarningIfNeeded(warnings, "tenant-daily-requests",
                    tenantRequestsToday, tenant.dailyRequestLimit());
        }

        // ── Check 3: User daily token budget ─────────────────
        if (user.dailyTokenBudget() != null) {
            long userTokensToday = budgetRepository
                    .sumUserTokensToday(tenantContext.userId());
            if (userTokensToday >= user.dailyTokenBudget()) {
                log.warn("User daily token budget exceeded: userId={} tokens={} budget={}",
                        tenantContext.userId(), userTokensToday,
                        user.dailyTokenBudget());
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Daily token budget exceeded. Resets at midnight.");
            }
            addWarningIfNeeded(warnings, "user-daily-tokens",
                    userTokensToday, user.dailyTokenBudget());
        }

        // ── Check 4: Tenant daily token budget ───────────────
        if (tenant.dailyTokenBudget() != null) {
            long tenantTokensToday = budgetRepository
                    .sumTenantTokensToday(tenantContext.tenantId());
            if (tenantTokensToday >= tenant.dailyTokenBudget()) {
                log.warn("Tenant daily token budget exceeded: tenantId={} tokens={} budget={}",
                        tenantContext.tenantId(), tenantTokensToday,
                        tenant.dailyTokenBudget());
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Tenant daily token budget exceeded. Resets at midnight.");
            }
            addWarningIfNeeded(warnings, "tenant-daily-tokens",
                    tenantTokensToday, tenant.dailyTokenBudget());
        }

        // ── Check 5: User monthly token budget ───────────────
        if (user.monthlyTokenBudget() != null) {
            long userTokensThisMonth = budgetRepository
                    .sumUserTokensThisMonth(tenantContext.userId());
            if (userTokensThisMonth >= user.monthlyTokenBudget()) {
                log.warn("User monthly token budget exceeded: userId={} tokens={} budget={}",
                        tenantContext.userId(), userTokensThisMonth,
                        user.monthlyTokenBudget());
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Monthly token budget exhausted. Contact your administrator.");
            }
            addWarningIfNeeded(warnings, "user-monthly-tokens",
                    userTokensThisMonth, user.monthlyTokenBudget());
        }

        // ── Check 6: Tenant monthly token budget ─────────────
        if (tenant.monthlyTokenBudget() != null) {
            long tenantTokensThisMonth = budgetRepository
                    .sumTenantTokensThisMonth(tenantContext.tenantId());
            if (tenantTokensThisMonth >= tenant.monthlyTokenBudget()) {
                log.warn("Tenant monthly token budget exceeded: tenantId={} tokens={} budget={}",
                        tenantContext.tenantId(), tenantTokensThisMonth,
                        tenant.monthlyTokenBudget());
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Tenant monthly token budget exhausted. Contact your administrator.");
            }
            addWarningIfNeeded(warnings, "tenant-monthly-tokens",
                    tenantTokensThisMonth, tenant.monthlyTokenBudget());
        }

        if (!warnings.isEmpty()) {
            log.info("Budget warnings for userId={}: {}",
                    tenantContext.userId(), warnings);
        }

        return new BudgetCheckResult(warnings);
    }

    /**
     * Adds a warning string to the list if usage exceeds warning threshold.
     * Warning format: "user-daily-requests at 85% (85/100)"
     *
     * @param warnings    list to add warning to
     * @param limitName   human-readable limit name for header
     * @param used        current usage
     * @param limit       maximum allowed
     */
    private void addWarningIfNeeded(List<String> warnings,
                                    String limitName,
                                    long used, long limit) {
        double usagePercent = (used * 100.0) / limit;
        if (usagePercent >= budgetProperties.getWarningThresholdPercent()) {
            warnings.add(String.format("%s at %.0f%% (%d/%d)",
                    limitName, usagePercent, used, limit));
        }
    }
}