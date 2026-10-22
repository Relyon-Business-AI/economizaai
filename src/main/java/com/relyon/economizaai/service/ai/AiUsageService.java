package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.repository.AiFindingRepository;
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
    private final AiFindingRepository findingRepository;
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

    /**
     * Comparação ENTRE MODELOS no período: custo/volume/confiabilidade (do usage log) +
     * qualidade (taxa de aceitação dos achados). É assim que se compara "mês do GPT" vs
     * "mês do Claude" — cada modelo aparece com seus números no período escolhido.
     */
    @Transactional(readOnly = true)
    public List<ModelComparison> modelComparison(int days) {
        var since = days == 0 ? null : LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 3650)));
        var findingStats = findingRepository.statsByModel(since).stream()
                .collect(java.util.stream.Collectors.toMap(row -> String.valueOf(row[0]), row -> row));
        return usageRepository.summarizeByModel(since).stream().map(usageRow -> {
            var model = String.valueOf(usageRow[0]);
            var calls = ((Number) usageRow[1]).longValue();
            var cost = toBigDecimal(usageRow[4]);
            var successCalls = ((Number) usageRow[5]).longValue();
            var avgLatencyMs = usageRow[6] == null ? null
                    : BigDecimal.valueOf(((Number) usageRow[6]).doubleValue()).setScale(0, RoundingMode.HALF_UP);
            var avgCostPerCall = calls == 0 ? BigDecimal.ZERO
                    : cost.divide(BigDecimal.valueOf(calls), 6, RoundingMode.HALF_UP);
            var successRate = calls == 0 ? null
                    : BigDecimal.valueOf(100.0 * successCalls / calls).setScale(1, RoundingMode.HALF_UP);
            var fs = findingStats.get(model);
            long created = 0, approved = 0, rejected = 0;
            BigDecimal avgConfidence = null, acceptanceRate = null;
            if (fs != null) {
                created = ((Number) fs[1]).longValue();
                approved = ((Number) fs[2]).longValue();
                rejected = ((Number) fs[3]).longValue();
                avgConfidence = toBigDecimal(fs[4]).setScale(3, RoundingMode.HALF_UP);
                var reviewed = approved + rejected;
                acceptanceRate = reviewed == 0 ? null
                        : BigDecimal.valueOf(100.0 * approved / reviewed).setScale(1, RoundingMode.HALF_UP);
            }
            return new ModelComparison(model, aiProviderFor(model), calls, cost, avgCostPerCall, successRate,
                    avgLatencyMs, created, approved, rejected, acceptanceRate, avgConfidence);
        }).toList();
    }

    /** Agregados do Hibernate variam entre BigDecimal/Double/Long — extrai robusto. */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bigDecimal) return bigDecimal;
        return BigDecimal.valueOf(((Number) value).doubleValue());
    }

    private String aiProviderFor(String model) {
        var lower = model == null ? "" : model.toLowerCase();
        return lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")
                ? "OPENAI" : "ANTHROPIC";
    }

    public record ModelComparison(String model, String provider, long calls, BigDecimal costUsd,
                                  BigDecimal avgCostPerCallUsd, BigDecimal successRatePct, BigDecimal avgLatencyMs,
                                  long findingsCreated, long findingsApproved, long findingsRejected,
                                  BigDecimal acceptanceRatePct, BigDecimal avgConfidence) {}

    public record ActivityUsage(String activity, long calls, long inputTokens, long outputTokens, BigDecimal costUsd) {}

    public record DailyUsage(String day, long calls, BigDecimal costUsd) {}

    public record UsageSummary(int days, long totalCalls, long totalInputTokens, long totalOutputTokens,
                               BigDecimal totalCostUsd, BigDecimal allTimeCostUsd,
                               BigDecimal budgetUsd, BigDecimal estimatedBalanceUsd,
                               List<ActivityUsage> byActivity, List<DailyUsage> byDay) {}
}
