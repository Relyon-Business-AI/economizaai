package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.model.CategorizationQualitySnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A categorization-quality data point for the trend view. */
public record CategorizationQualitySnapshotResponse(
        LocalDateTime recordedAt,
        String trigger,
        BigDecimal accuracyPct,
        int benchmarkTotal,
        int benchmarkCorrect,
        int catalogProducts,
        int catalogCategorized,
        BigDecimal catalogCoveragePct,
        BigDecimal brandAccuracyPct,
        BigDecimal quantityAccuracyPct
) {
    public static CategorizationQualitySnapshotResponse from(CategorizationQualitySnapshot snapshot) {
        return new CategorizationQualitySnapshotResponse(
                BrazilClock.fromUtc(snapshot.getCreatedAt()),
                snapshot.getTrigger().name(),
                snapshot.getAccuracyPct(),
                snapshot.getBenchmarkTotal(),
                snapshot.getBenchmarkCorrect(),
                snapshot.getCatalogProducts(),
                snapshot.getCatalogCategorized(),
                snapshot.getCatalogCoveragePct(),
                snapshot.getBrandAccuracyPct(),
                snapshot.getQuantityAccuracyPct());
    }
}
