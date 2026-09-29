package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.dto.response.CategorizationQualitySnapshotResponse;
import com.relyon.economizaai.model.CategorizationQualitySnapshot;
import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.repository.CategorizationQualitySnapshotRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.ContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Persists categorization-quality snapshots so we can track the trend over time.
 * A snapshot pairs the golden-set accuracy (cascade correctness) with live
 * catalog coverage (% of real products that have a category) and is written on
 * every benchmark run and every backfill. On each write it also checks for
 * quality drift vs the recent baseline and alerts the admin so a regression
 * surfaces immediately instead of only when someone looks at the dashboard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationQualityService {

    private static final int MAX_HISTORY = 500;

    private final CategorizationBenchmarkService benchmarkService;
    private final ProductRepository productRepository;
    private final CategorizationQualitySnapshotRepository snapshotRepository;
    private final ContactService contactService;

    /** How many prior snapshots form the drift baseline (trailing average). */
    @Value("${economizaai.categorizer.quality.drift.window:7}")
    private int driftWindow;

    /** Need at least this many prior snapshots before drift alerting kicks in (avoids cold-start noise). */
    @Value("${economizaai.categorizer.quality.drift.min-history:3}")
    private int driftMinHistory;

    /** Alert when accuracy falls this many points below the baseline average. */
    @Value("${economizaai.categorizer.quality.drift.accuracy-drop-pts:2.0}")
    private double accuracyDropAlertPts;

    /** Alert when catalog coverage falls this many points below the baseline average. */
    @Value("${economizaai.categorizer.quality.drift.coverage-drop-pts:3.0}")
    private double coverageDropAlertPts;

    @Transactional
    public CategorizationQualitySnapshotResponse measureAndRecord(CategorizationQualityTrigger trigger) {
        return record(trigger, benchmarkService.run());
    }

    @Transactional
    public CategorizationQualitySnapshotResponse record(CategorizationQualityTrigger trigger,
                                                        CategorizationBenchmarkResponse report) {
        var catalogProducts = productRepository.count();
        var catalogCategorized = productRepository.countByCategoryNotNull();
        var coveragePct = catalogProducts == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(catalogCategorized * 100.0 / catalogProducts).setScale(2, RoundingMode.HALF_UP);

        var snapshot = CategorizationQualitySnapshot.builder()
                .trigger(trigger)
                .accuracyPct(BigDecimal.valueOf(report.accuracyPct()).setScale(2, RoundingMode.HALF_UP))
                .benchmarkTotal(report.total())
                .benchmarkCorrect(report.correct())
                .catalogProducts((int) catalogProducts)
                .catalogCategorized((int) catalogCategorized)
                .catalogCoveragePct(coveragePct)
                .brandAccuracyPct(BigDecimal.valueOf(report.brandAccuracyPct()).setScale(2, RoundingMode.HALF_UP))
                .quantityAccuracyPct(BigDecimal.valueOf(report.quantityAccuracyPct()).setScale(2, RoundingMode.HALF_UP))
                .build();
        var saved = snapshotRepository.save(snapshot);
        log.info("categorizer.quality.snapshot trigger={} accuracyPct={} coveragePct={} catalog={}/{}",
                trigger, snapshot.getAccuracyPct(), coveragePct, catalogCategorized, catalogProducts);
        alertIfDrift(saved);
        return CategorizationQualitySnapshotResponse.from(saved);
    }

    /**
     * Compares the just-recorded snapshot against the trailing baseline and emails the
     * admin when accuracy/coverage dropped beyond the configured margin. Isolated in a
     * try/catch so a drift-check failure never breaks the snapshot write.
     */
    private void alertIfDrift(CategorizationQualitySnapshot current) {
        try {
            var recent = snapshotRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, driftWindow + 1));
            var baseline = recent.stream().filter(snapshot -> !Objects.equals(snapshot.getId(), current.getId())).toList();
            if (baseline.size() < driftMinHistory) return;

            var baselineAccuracy = average(baseline, CategorizationQualitySnapshot::getAccuracyPct);
            var baselineCoverage = average(baseline, CategorizationQualitySnapshot::getCatalogCoveragePct);
            var accuracyNow = current.getAccuracyPct().doubleValue();
            var coverageNow = current.getCatalogCoveragePct().doubleValue();
            var accuracyDrop = baselineAccuracy - accuracyNow;
            var coverageDrop = baselineCoverage - coverageNow;

            if (accuracyDrop < accuracyDropAlertPts && coverageDrop < coverageDropAlertPts) return;

            log.warn("categorizer.quality.drift_alert accuracyNow={} baselineAccuracy={} coverageNow={} baselineCoverage={}",
                    accuracyNow, baselineAccuracy, coverageNow, baselineCoverage);
            contactService.notifyAdmin("Queda de qualidade na categorização", String.format(
                    "A qualidade da categorização caiu vs a média recente (últimos %d snapshots).%n%n"
                            + "Acurácia: %.2f%% agora vs %.2f%% de média (queda de %.2f pts)%n"
                            + "Cobertura: %.2f%% agora vs %.2f%% de média (queda de %.2f pts)%n%n"
                            + "Gatilho do snapshot: %s. Verifique regras recém-criadas/auto-promovidas e o benchmark.",
                    baseline.size(), accuracyNow, baselineAccuracy, accuracyDrop,
                    coverageNow, baselineCoverage, coverageDrop, current.getTrigger()));
        } catch (RuntimeException ex) {
            log.error("categorizer.quality.drift_check_failed", ex);
        }
    }

    private static double average(List<CategorizationQualitySnapshot> snapshots,
                                 Function<CategorizationQualitySnapshot, BigDecimal> field) {
        return snapshots.stream().map(field).filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
    }

    @Transactional(readOnly = true)
    public List<CategorizationQualitySnapshotResponse> history(int limit) {
        var capped = Math.max(1, Math.min(limit, MAX_HISTORY));
        return snapshotRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, capped)).stream()
                .map(CategorizationQualitySnapshotResponse::from)
                .toList();
    }
}
