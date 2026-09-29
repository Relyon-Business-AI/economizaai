package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizaai.model.CategorizationQualitySnapshot;
import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizaai.repository.CategorizationQualitySnapshotRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.ContactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizationQualityServiceTest {

    @Mock private CategorizationBenchmarkService benchmarkService;
    @Mock private ProductRepository productRepository;
    @Mock private CategorizationQualitySnapshotRepository snapshotRepository;
    @Mock private ContactService contactService;

    private CategorizationQualityService service() {
        var service = new CategorizationQualityService(
                benchmarkService, productRepository, snapshotRepository, contactService);
        ReflectionTestUtils.setField(service, "driftWindow", 7);
        ReflectionTestUtils.setField(service, "driftMinHistory", 3);
        ReflectionTestUtils.setField(service, "accuracyDropAlertPts", 2.0);
        ReflectionTestUtils.setField(service, "coverageDropAlertPts", 3.0);
        return service;
    }

    /** Baseline history where every snapshot sits at the given accuracy/coverage. */
    private List<CategorizationQualitySnapshot> baselineAt(int count, double accuracy, double coverage) {
        return IntStream.range(0, count).mapToObj(index -> {
            CategorizationQualitySnapshot snapshot = CategorizationQualitySnapshot.builder()
                    .id(UUID.randomUUID())
                    .accuracyPct(BigDecimal.valueOf(accuracy))
                    .catalogCoveragePct(BigDecimal.valueOf(coverage))
                    .build();
            return snapshot;
        }).toList();
    }

    private CategorizationBenchmarkResponse report(double categoryPct, double brandPct, double quantityPct) {
        return new CategorizationBenchmarkResponse(
                10, (int) Math.round(categoryPct / 10), categoryPct, 0, 0,
                5, (int) Math.round(brandPct / 20), brandPct,
                5, (int) Math.round(quantityPct / 20), quantityPct,
                List.of());
    }

    @Test
    void record_persistsAllFieldAccuraciesAndCoverage() {
        when(productRepository.count()).thenReturn(200L);
        when(productRepository.countByCategoryNotNull()).thenReturn(150L);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().record(CategorizationQualityTrigger.BENCHMARK, report(100.0, 80.0, 90.0));

        var captor = ArgumentCaptor.forClass(CategorizationQualitySnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(0, new BigDecimal("100.00").compareTo(saved.getAccuracyPct()));
        assertEquals(0, new BigDecimal("75.00").compareTo(saved.getCatalogCoveragePct())); // 150/200
        assertEquals(0, new BigDecimal("80.00").compareTo(saved.getBrandAccuracyPct()));
        assertEquals(0, new BigDecimal("90.00").compareTo(saved.getQuantityAccuracyPct()));
        assertEquals(CategorizationQualityTrigger.BENCHMARK, saved.getTrigger());
    }

    @Test
    void measureAndRecord_runsBenchmark() {
        when(benchmarkService.run()).thenReturn(report(90.0, 0.0, 0.0));
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

        var response = service().record(CategorizationQualityTrigger.BENCHMARK, report(0.0, 0.0, 0.0));

        assertEquals(0, BigDecimal.ZERO.compareTo(response.catalogCoveragePct()));
    }

    @Test
    void record_alertsAdminWhenAccuracyDropsBelowBaseline() {
        when(productRepository.count()).thenReturn(200L);
        when(productRepository.countByCategoryNotNull()).thenReturn(150L); // 75% coverage, same as baseline
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(baselineAt(5, 98.0, 75.0));

        service().record(CategorizationQualityTrigger.BACKFILL, report(90.0, 80.0, 90.0)); // 90% vs 98% baseline

        verify(contactService).notifyAdmin(anyString(), contains("Acurácia"));
    }

    @Test
    void record_noAlertWhenQualityStable() {
        when(productRepository.count()).thenReturn(200L);
        when(productRepository.countByCategoryNotNull()).thenReturn(150L);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(baselineAt(5, 98.0, 75.0));

        service().record(CategorizationQualityTrigger.BACKFILL, report(100.0, 80.0, 90.0)); // above baseline

        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void record_noAlertWhenHistoryTooShort() {
        when(productRepository.count()).thenReturn(200L);
        when(productRepository.countByCategoryNotNull()).thenReturn(150L);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(baselineAt(2, 98.0, 75.0)); // < minHistory

        service().record(CategorizationQualityTrigger.BACKFILL, report(80.0, 80.0, 90.0)); // big drop but no baseline

        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }
}
