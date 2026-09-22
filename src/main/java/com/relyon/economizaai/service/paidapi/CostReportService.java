package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.dto.response.CostReportResponse;
import com.relyon.economizaai.dto.response.CostReportResponse.ServiceSpendLine;
import com.relyon.economizaai.dto.response.CostReportResponse.StateSpendLine;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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
    private final Clock clock = Clock.systemUTC();

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

        return new CostReportResponse(days, totalCalls, totalCents, reais(totalCents),
                properties.getDailyGlobalBudgetCents(), reais(spentTodayCents), byService, byState);
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
