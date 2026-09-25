package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.dto.response.CategorizationQualitySnapshotResponse;
import com.relyon.economizaai.model.CategorizationQualitySnapshot;
import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.repository.CategorizationQualitySnapshotRepository;
import com.relyon.economizaai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Persists categorization-quality snapshots so we can track the trend over time.
 * A snapshot pairs the golden-set accuracy (cascade correctness) with live
 * catalog coverage (% of real products that have a category) and is written on
 * every benchmark run and every backfill.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationQualityService {

    private static final int MAX_HISTORY = 500;

    private final CategorizationBenchmarkService benchmarkService;
    private final ProductRepository productRepository;
    private final CategorizationQualitySnapshotRepository snapshotRepository;

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
        return CategorizationQualitySnapshotResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<CategorizationQualitySnapshotResponse> history(int limit) {
        var capped = Math.max(1, Math.min(limit, MAX_HISTORY));
        return snapshotRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, capped)).stream()
                .map(CategorizationQualitySnapshotResponse::from)
                .toList();
    }
}
