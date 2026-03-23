package com.ai.spring_lens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Budget enforcement configuration properties.
 *
 * warningThresholdPercent — percentage of budget at which warning
 * headers are added to response. Default 80%.
 *
 * Example: if monthly_token_budget = 200,000 and user has consumed
 * 164,000 tokens (82%) — warning header added to response.
 *
 * Configurable via:
 * springlens.budget.warning-threshold-percent
 */
@Data
@Component
@ConfigurationProperties(prefix = "springlens.budget")
public class BudgetProperties {
    private double warningThresholdPercent = 80.0;
}