package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.repository.AiUsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Aggregations for the admin AI-spend panel: where the money/tokens are going. */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private final AiUsageLogRepository usageRepository;

    @Transactional(readOnly = true)
    public UsageSummary summary(int days) {
        var since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 365)));
        var byActivity = usageRepository.summarizeByActivity(since).stream()
                .map(row -> new ActivityUsage(
                        String.valueOf(row[0]),
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue(),
                        ((Number) row[3]).longValue(),
                        (BigDecimal) row[4]))
                .toList();
        var byDay = usageRepository.summarizeByDay(since).stream()
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
        return new UsageSummary(days, totalCalls, totalInput, totalOutput, totalCost, byActivity, byDay);
    }

    public record ActivityUsage(String activity, long calls, long inputTokens, long outputTokens, BigDecimal costUsd) {}

    public record DailyUsage(String day, long calls, BigDecimal costUsd) {}

    public record UsageSummary(int days, long totalCalls, long totalInputTokens, long totalOutputTokens,
                               BigDecimal totalCostUsd, List<ActivityUsage> byActivity, List<DailyUsage> byDay) {}
}
