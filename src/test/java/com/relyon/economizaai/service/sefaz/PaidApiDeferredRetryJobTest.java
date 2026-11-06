package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.ReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidApiDeferredRetryJobTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptService receiptService;

    private PaidApiDeferredRetryJob job;

    @BeforeEach
    void setUp() {
        job = new PaidApiDeferredRetryJob(receiptRepository, receiptService, 7);
    }

    @Test
    void requeuesOnlyRecentNotasBlockedByAPaidApiGuard() {
        var recent = receiptCreated(LocalDateTime.now().minusDays(2));
        var stale = receiptCreated(LocalDateTime.now().minusDays(10));
        when(receiptRepository.findByStatusAndParseErrorReasonContainingIgnoreCase(
                ReceiptStatus.FAILED_PARSE, PaidApiDeferredRetryJob.PAID_API_REASON_PREFIX))
                .thenReturn(List.of(recent, stale));

        job.retryNotasBlockedByPaidApiGuards();

        verify(receiptService).adminRetryFailedScans(List.of(recent.getId()));
    }

    @Test
    void nothingBlocked_doesNothing() {
        when(receiptRepository.findByStatusAndParseErrorReasonContainingIgnoreCase(any(), any()))
                .thenReturn(List.of());

        job.retryNotasBlockedByPaidApiGuards();

        verify(receiptService, never()).adminRetryFailedScans(any());
    }

    private static Receipt receiptCreated(LocalDateTime createdAt) {
        var receipt = new Receipt();
        receipt.setId(UUID.randomUUID());
        receipt.setCreatedAt(createdAt);
        return receipt;
    }
}
