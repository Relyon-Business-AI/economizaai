package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.model.CategorizationQualitySnapshot;
import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.repository.CategorizationQualitySnapshotRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizationQualityServiceTest {

    @Mock private CategorizationBenchmarkService benchmarkService;
    @Mock private ProductRepository productRepository;
    @Mock private MlClassifierService mlClassifier;
    @Mock private CategorizationQualitySnapshotRepository snapshotRepository;

    private CategorizationQualityService service() {
        return new CategorizationQualityService(benchmarkService, productRepository, mlClassifier, snapshotRepository);
    }

    /** Golden-set report: category accuracyPct + brand/quantity/ml shadow %s. */
    private CategorizationBenchmarkResponse report(double categoryPct, double brandPct,
                                                  double quantityPct, double mlPct) {
        return new CategorizationBenchmarkResponse(
                10, (int) Math.round(categoryPct / 10), categoryPct, 0, 0,
                5, (int) Math.round(brandPct / 20), brandPct,
                5, (int) Math.round(quantityPct / 20), quantityPct,
                10, (int) Math.round(mlPct / 10), mlPct,
                List.of());
    }

    @Test
    void record_persistsAllFieldAccuraciesAndCoverage() {
        when(productRepository.count()).thenReturn(200L);
        when(productRepository.countByCategoryNotNull()).thenReturn(150L);
        when(mlClassifier.isReady()).thenReturn(true);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().record(CategorizationQualityTrigger.BENCHMARK, report(100.0, 80.0, 90.0, 40.0));

        var captor = ArgumentCaptor.forClass(CategorizationQualitySnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(0, new BigDecimal("100.00").compareTo(saved.getAccuracyPct()));
        assertEquals(0, new BigDecimal("75.00").compareTo(saved.getCatalogCoveragePct())); // 150/200
        assertEquals(0, new BigDecimal("80.00").compareTo(saved.getBrandAccuracyPct()));
        assertEquals(0, new BigDecimal("90.00").compareTo(saved.getQuantityAccuracyPct()));
        assertEquals(0, new BigDecimal("40.00").compareTo(saved.getMlAccuracyPct()));
        assertEquals(CategorizationQualityTrigger.BENCHMARK, saved.getTrigger());
    }

    @Test
    void measureAndRecord_runsBenchmark() {
        when(benchmarkService.run()).thenReturn(report(90.0, 0.0, 0.0, 0.0));
        when(productRepository.count()).thenReturn(0L);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().measureAndRecord(CategorizationQualityTrigger.BACKFILL);

        verify(benchmarkService).run();
        verify(snapshotRepository).save(any());
    }

    @Test
    void record_zeroCatalog_isZeroCoverageNotDivByZero() {
        when(productRepository.count()).thenReturn(0L);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().record(CategorizationQualityTrigger.BENCHMARK, report(0.0, 0.0, 0.0, 0.0));

        assertEquals(0, BigDecimal.ZERO.compareTo(response.catalogCoveragePct()));
    }
}
