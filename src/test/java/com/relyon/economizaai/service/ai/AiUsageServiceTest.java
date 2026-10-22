package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.AiUsageLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUsageServiceTest {

    @Mock private AiUsageLogRepository usageRepository;
    @Mock private AiFindingRepository findingRepository;
    @Mock private AiGateway aiGateway;
    @InjectMocks private AiUsageService service;

    @Test
    void modelComparison_joinsCostWithAcceptanceAndDerivesProvider() {
        // usage: model, calls, in, out, cost, successCalls
        when(usageRepository.summarizeByModel(any(LocalDateTime.class))).thenReturn(List.of(
                new Object[]{"claude-sonnet-4-6", 100L, 80000L, 20000L, new BigDecimal("0.54"), 98L, 1800.0},
                new Object[]{"gpt-5", 50L, 40000L, 10000L, new BigDecimal("0.15"), 50L, 950.0}));
        // findings: model, created, approved, rejected, avgConfidence
        when(findingRepository.statsByModel(any(LocalDateTime.class))).thenReturn(List.of(
                new Object[]{"claude-sonnet-4-6", 40L, 30L, 10L, new BigDecimal("0.910")},
                new Object[]{"gpt-5", 20L, 18L, 2L, new BigDecimal("0.880")}));

        var result = service.modelComparison(30);

        assertEquals(2, result.size());
        var sonnet = result.stream().filter(r -> r.model().equals("claude-sonnet-4-6")).findFirst().orElseThrow();
        assertEquals("ANTHROPIC", sonnet.provider());
        assertEquals(0, sonnet.acceptanceRatePct().compareTo(new BigDecimal("75.0"))); // 30/40
        assertEquals(0, sonnet.successRatePct().compareTo(new BigDecimal("98.0")));
        assertEquals(0, sonnet.avgCostPerCallUsd().compareTo(new BigDecimal("0.005400")));
        assertEquals(0, sonnet.avgLatencyMs().compareTo(new BigDecimal("1800"))); // latência média

        var gpt = result.stream().filter(r -> r.model().equals("gpt-5")).findFirst().orElseThrow();
        assertEquals("OPENAI", gpt.provider());
        assertEquals(0, gpt.acceptanceRatePct().compareTo(new BigDecimal("90.0"))); // 18/20
    }

    @Test
    void modelComparison_handlesDoubleAggregatesFromHibernate() {
        // Hibernate pode devolver avg()/sum() como Double — não pode estourar ClassCastException.
        when(usageRepository.summarizeByModel(any(LocalDateTime.class))).thenReturn(List.<Object[]>of(
                new Object[]{"gpt-5", 10L, 5000L, 1000L, 0.15d, 10L, 950.0d}));  // custo como Double
        when(findingRepository.statsByModel(any(LocalDateTime.class))).thenReturn(List.<Object[]>of(
                new Object[]{"gpt-5", 5L, 4L, 1L, 0.88d}));  // avgConfidence como Double

        var result = service.modelComparison(30);

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).costUsd().compareTo(new BigDecimal("0.15")));
        assertEquals(0, result.get(0).avgConfidence().compareTo(new BigDecimal("0.880")));
        assertEquals(0, result.get(0).acceptanceRatePct().compareTo(new BigDecimal("80.0")));
    }

    @Test
    void modelComparison_handlesModelWithoutReviewedFindings() {
        when(usageRepository.summarizeByModel(any(LocalDateTime.class))).thenReturn(List.<Object[]>of(
                new Object[]{"gpt-5-nano", 10L, 5000L, 1000L, new BigDecimal("0.01"), 10L, 400.0}));
        when(findingRepository.statsByModel(any(LocalDateTime.class))).thenReturn(List.<Object[]>of());

        var result = service.modelComparison(30);

        assertEquals(1, result.size());
        assertNull(result.get(0).acceptanceRatePct()); // sem achados revisados → null, não 0 enganoso
        assertEquals(0, result.get(0).findingsCreated());
    }
}
