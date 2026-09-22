package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionHealthServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @InjectMocks private IngestionHealthService service;

    @Test
    void computesStatusMixSuccessRateStuckAndPerUf() {
        when(receiptRepository.statusBreakdownSince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                new Object[]{ReceiptStatus.CONFIRMED, 60L},
                new Object[]{ReceiptStatus.PENDING_CONFIRMATION, 10L},
                new Object[]{ReceiptStatus.FAILED_PARSE, 20L},
                new Object[]{ReceiptStatus.PROCESSING, 5L}));
        when(receiptRepository.ufStatusBreakdownSince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                new Object[]{UnidadeFederativa.RS, ReceiptStatus.CONFIRMED, 40L},
                new Object[]{UnidadeFederativa.RS, ReceiptStatus.FAILED_PARSE, 10L},
                new Object[]{UnidadeFederativa.PE, ReceiptStatus.CONFIRMED, 20L}));
        when(receiptRepository.errorReasonBreakdownSince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                new Object[]{"receipt.parse.failed", 12L},
                new Object[]{"receipt.processing.timeout", 5L},
                new Object[]{"receipt.device_fetch.timeout", 3L}));

        var report = service.report(30, false);

        assertEquals(95L, report.totalReceipts());
        assertEquals(70L, report.parsedOk());
        assertEquals(20L, report.failedParse());
        assertEquals(5L, report.inFlight());
        assertEquals(0.7778d, report.successRate(), 0.001); // 70 / (70 + 20)
        assertEquals(5L, report.stuckProcessingTimeouts());
        assertEquals(3L, report.stuckDeviceFetchTimeouts());
        assertEquals(2, report.byUf().size());
        assertEquals("RS", report.byUf().get(0).uf()); // ordered by total desc (50 > 20)
        assertEquals(50L, report.byUf().get(0).total());
        assertEquals(0.8d, report.byUf().get(0).successRate(), 0.001); // 40 / (40 + 10)
        assertEquals(3, report.topErrors().size());
        assertEquals(60L, report.byStatus().get("CONFIRMED"));
    }
}
