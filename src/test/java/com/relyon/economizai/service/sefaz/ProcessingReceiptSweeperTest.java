package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessingReceiptSweeperTest {

    @Mock private ReceiptRepository receiptRepository;

    private Receipt stuckReceipt() {
        return Receipt.builder()
                .id(UUID.randomUUID())
                .status(ReceiptStatus.PROCESSING)
                .build();
    }

    @Test
    void sweep_failsStuckProcessingReceipts() {
        var sweeper = new ProcessingReceiptSweeper(receiptRepository, 10, 15);
        var first = stuckReceipt();
        var second = stuckReceipt();
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of());

        sweeper.sweep();

        assertEquals(ReceiptStatus.FAILED_PARSE, first.getStatus());
        assertEquals(ReceiptStatus.FAILED_PARSE, second.getStatus());
        assertEquals("receipt.processing.timeout", first.getParseErrorReason());
        verify(receiptRepository).saveAll(List.of(first, second));
    }

    @Test
    void sweep_failsStrandedNeedsDeviceFetchReceipts() {
        var sweeper = new ProcessingReceiptSweeper(receiptRepository, 10, 15);
        var stranded = Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.NEEDS_DEVICE_FETCH).build();
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of(stranded));

        sweeper.sweep();

        assertEquals(ReceiptStatus.FAILED_PARSE, stranded.getStatus());
        assertEquals("receipt.device_fetch.timeout", stranded.getParseErrorReason());
        verify(receiptRepository).saveAll(List.of(stranded));
    }

    @Test
    void sweep_noopWhenNothingStuck() {
        var sweeper = new ProcessingReceiptSweeper(receiptRepository, 10, 15);
        when(receiptRepository.findByStatusAndCreatedAtBefore(any(ReceiptStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        sweeper.sweep();

        verify(receiptRepository, never()).saveAll(any());
    }

    @Test
    void sweep_usesCutoffOlderThanTimeout() {
        var sweeper = new ProcessingReceiptSweeper(receiptRepository, 10, 15);
        var cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.PROCESSING), cutoffCaptor.capture()))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of());

        sweeper.sweep();

        var cutoff = cutoffCaptor.getValue();
        assertTrue(cutoff.isBefore(LocalDateTime.now().minusMinutes(9)),
                "cutoff should be at least timeoutMinutes in the past");
    }
}
