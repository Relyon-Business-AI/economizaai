package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Where the money goes: total paid-API spend over a window, broken down by
 * service (captcha vs Infosimples) and by state (UF). Cents are the source of
 * truth; reais are the same value scaled for readability.
 */
public record CostReportResponse(
        int windowDays,
        long totalCalls,
        long totalCostCents,
        BigDecimal totalCostReais,
        long dailyGlobalBudgetCents,
        BigDecimal spentTodayReais,
        // Live Infosimples account status (R$) — null when the provider is
        // disabled or its account endpoint didn't answer. consumoMes is what the
        // month actually BILLED (unexecuted failures aren't charged); franquia is
        // the monthly minimum that sweeps unused credit.
        BigDecimal infosimplesSaldo,
        BigDecimal infosimplesConsumoMes,
        BigDecimal infosimplesFranquiaMinima,
        List<ServiceSpendLine> byService,
        List<StateSpendLine> byState,
        // LLM/AI spend over the window. Lives in a separate ledger (ai_usage_log,
        // real token cost in USD) — converted to R$ here. Split between what a user's
        // own action triggered (photo/rescue) and the shared platform cost
        // (categorization + admin sweeps) which is amortized across active users.
        AiCostSummary ai) {

    public record ServiceSpendLine(String service, long calls, long failures,
                                   long costCents, BigDecimal costReais) {
    }

    public record StateSpendLine(String uf, long calls, long costCents, BigDecimal costReais) {
    }

    public record AiCostSummary(
            BigDecimal totalReais,            // all LLM cost in the window (user + platform)
            BigDecimal userReais,             // attributable to a user's action (photo extract + HTML rescue)
            BigDecimal platformReais,         // shared cost (categorization + admin sweeps) — amortizable
            long activeUsers,                 // basis for amortizing the platform cost
            BigDecimal platformPerUserReais,  // platformReais / activeUsers
            BigDecimal usdBrlRate) {          // the conversion rate used (for transparency)
    }
}
