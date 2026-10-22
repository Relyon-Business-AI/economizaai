package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.dto.response.CostReportResponse;
import com.relyon.economizaai.dto.response.CostReportResponse.AiCostSummary;
import com.relyon.economizaai.dto.response.CostReportResponse.ServiceSpendLine;
import com.relyon.economizaai.dto.response.CostReportResponse.StateSpendLine;
import com.relyon.economizaai.repository.AiUsageLogRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.sefaz.InfosimplesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Reads the {@code paid_api_call} ledger into an admin-facing cost report — the
 * "how and where are we spending" view over captcha + Infosimples. Read-only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostReportService {

    private final PaidApiCallRepository repository;
    private final PaidApiGuardProperties properties;
    private final Optional<InfosimplesService> infosimples;
    private final AiUsageLogRepository aiUsageLogRepository;
    private final UserRepository userRepository;
    private final Clock clock = Clock.systemUTC();

    /** USD→BRL rate used to convert the LLM ledger (priced in USD) into R$ for the panel. */
    @Value("${economizaai.costs.usd-brl-rate:5.50}")
    private BigDecimal usdBrlRate;

    public CostReportResponse report(int windowDays) {
        var days = Math.max(1, windowDays);
        var since = OffsetDateTime.now(clock).minusDays(days);

        var byService = repository.spendByService(since).stream()
                .map(row -> new ServiceSpendLine(row.getService().name(), row.getCalls(),
                        row.getFailures(), row.getCostCents(), reais(row.getCostCents())))
                .toList();
        var byState = repository.spendByState(since).stream()
                .map(row -> new StateSpendLine(uf(row.getUf()), row.getCalls(),
                        row.getCostCents(), reais(row.getCostCents())))
                .toList();

        var totalCalls = byService.stream().mapToLong(ServiceSpendLine::calls).sum();
        var totalCents = byService.stream().mapToLong(ServiceSpendLine::costCents).sum();
        var spentTodayCents = repository.sumCostCentsSince(startOfTodayUtc());
        // Live provider account so the admin sees the runway next to the spend:
        // saldo, billed month usage and the monthly minimum franchise. A free
        // management call; nulls (hidden in the FE) when disabled/unreachable.
        var account = infosimples.flatMap(InfosimplesService::fetchAccount).orElse(null);

        return new CostReportResponse(days, totalCalls, totalCents, reais(totalCents),
                properties.getDailyGlobalBudgetCents(), reais(spentTodayCents),
                account == null ? null : account.balance(),
                account == null ? null : account.currentUsage(),
                account == null ? null : account.minBill(),
                byService, byState, aiSummary(days));
    }

    /** LLM spend (ai_usage_log, USD) → R$, split user-triggered vs amortizable platform. */
    private AiCostSummary aiSummary(int days) {
        var since = LocalDateTime.now().minusDays(days);
        var totalUsd = aiUsageLogRepository.totalCostUsdSince(since);
        var platformUsd = aiUsageLogRepository.platformCostUsdSince(since);
        var userUsd = totalUsd.subtract(platformUsd);
        var platformReais = usdToReais(platformUsd);
        var activeUsers = userRepository.count();
        var platformPerUser = activeUsers > 0
                ? platformReais.divide(BigDecimal.valueOf(activeUsers), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new AiCostSummary(usdToReais(totalUsd), usdToReais(userUsd), platformReais,
                activeUsers, platformPerUser, usdBrlRate);
    }

    private BigDecimal usdToReais(BigDecimal usd) {
        return (usd == null ? BigDecimal.ZERO : usd).multiply(usdBrlRate).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal reais(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }

    private static String uf(String uf) {
        return uf == null ? "?" : uf;
    }

    private OffsetDateTime startOfTodayUtc() {
        return OffsetDateTime.now(clock).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
