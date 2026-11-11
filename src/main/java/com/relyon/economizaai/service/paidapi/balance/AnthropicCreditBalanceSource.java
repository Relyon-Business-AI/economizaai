package com.relyon.economizaai.service.paidapi.balance;

import com.relyon.economizaai.repository.AiUsageLogRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Anthropic exposes no balance API, so the "balance" is estimated the same way the admin
 * spend panel does: the configured prepaid credit ({@code ECONOMIZAAI_AI_BUDGET_USD})
 * minus the all-time AI spend in the usage log. Skipped when the credit is 0/unset.
 * After a recharge the owner raises the configured credit — that re-arms the alerts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicCreditBalanceSource implements ProviderBalanceSource {

    private final AiGateway aiGateway;
    private final AiUsageLogRepository usageRepository;

    @Override
    public String providerKey() {
        return "ANTHROPIC";
    }

    @Override
    public String displayName() {
        return "IA (Anthropic)";
    }

    @Override
    public String currencySymbol() {
        return "US$";
    }

    @Override
    public boolean isConfigured() {
        return aiGateway.budgetUsd() > 0;
    }

    @Override
    public Optional<BigDecimal> fetchBalance() {
        try {
            return configuredCredit().map(credit -> credit.subtract(usageRepository.totalCostAllTime()));
        } catch (RuntimeException ex) {
            log.warn("provider_balance.anthropic.failed reason={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public Optional<BigDecimal> configuredCredit() {
        var budgetUsd = aiGateway.budgetUsd();
        return budgetUsd > 0 ? Optional.of(BigDecimal.valueOf(budgetUsd)) : Optional.empty();
    }

    @Override
    public BigDecimal exhaustedAtOrBelow() {
        return new BigDecimal("0.05");
    }

    @Override
    public BigDecimal minimumTopUp() {
        return BigDecimal.ONE;
    }

    @Override
    public String whatStops() {
        return "nota por foto, resgate de nota e categorização por IA param";
    }
}
