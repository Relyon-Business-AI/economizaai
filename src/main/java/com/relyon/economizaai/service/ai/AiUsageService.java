package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.repository.AiUsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/** Aggregations for the admin AI-spend panel: where the money/tokens are going. */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private final AiUsageLogRepository usageRepository;
    private final AiGateway aiGateway;

    /**
     * Usage summary for the given period.
     * days=0 → all time (no date filter).
     */
    @Transactional(readOnly = true)
    public UsageSummary summary(int days) {
        List<Object[]> byActivityRows;
        List<Object[]> byDayRows;
        if (days == 0) {
            byActivityRows = usageRepository.summarizeByActivityAllTime();
            byDayRows = usageRepository.summarizeByDayAllTime();
        } else {
            var since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 3650)));
            byActivityRows = usageRepository.summarizeByActivity(since);
            byDayRows = usageRepository.summarizeByDay(since);
        }
        var byActivity = byActivityRows.stream()
                .map(row -> new ActivityUsage(
                        String.valueOf(row[0]),
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue(),
                        ((Number) row[3]).longValue(),
                        (BigDecimal) row[4]))
                .toList();
        var byDay = byDayRows.stream()
                .map(row -> new DailyUsage(
                        String.valueOf(row[0]),
                        ((Number) row[1]).longValue(),
                        (BigDecimal) row[2]))
                .toList();
        var totalCalls = byActivity.stream().mapToLong(ActivityUsage::calls).sum();
        var totalInput = byActivity.stream().mapToLong(ActivityUsage::inputTokens).sum();
        var totalOutput = byActivity.stream().mapToLong(ActivityUsage::outputTokens).sum();
        var totalCost = byActivity.stream().map(ActivityUsage::costUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var allTimeCost = usageRepository.totalCostAllTime();
        var budgetUsd = aiGateway.budgetUsd();
        var estimatedBalance = budgetUsd > 0
                ? BigDecimal.valueOf(budgetUsd).subtract(allTimeCost).setScale(4, RoundingMode.HALF_UP)
                : null;
        return new UsageSummary(days, totalCalls, totalInput, totalOutput, totalCost,
                allTimeCost, budgetUsd > 0 ? BigDecimal.valueOf(budgetUsd).setScale(2, RoundingMode.HALF_UP) : null,
                estimatedBalance, byActivity, byDay);
    }

    public record ActivityUsage(String activity, long calls, long inputTokens, long outputTokens, BigDecimal costUsd) {}

    public record DailyUsage(String day, long calls, BigDecimal costUsd) {}

    public record UsageSummary(int days, long totalCalls, long totalInputTokens, long totalOutputTokens,
                               BigDecimal totalCostUsd, BigDecimal allTimeCostUsd,
                               BigDecimal budgetUsd, BigDecimal estimatedBalanceUsd,
                               List<ActivityUsage> byActivity, List<DailyUsage> byDay) {}
}
