package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingReceiptAutoConfirmerTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptService receiptService;

    private Receipt pendingReceipt() {
        return Receipt.builder()
                .id(UUID.randomUUID())
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .build();
    }

    @Test
    void sweep_confirmsStalePendingReceiptsByUpdatedAt() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, true, 6);
        var first = pendingReceipt();
        var second = pendingReceipt();
        // updatedAt, not createdAt: an old nota rescued by retry is fresh by the
        // status flip and deserves the full review window before auto-confirm.
        when(receiptRepository.findByStatusAndUpdatedAtBefore(
                eq(ReceiptStatus.PENDING_CONFIRMATION), any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));

        confirmer.sweep();

        verify(receiptService).confirmStale(first.getId());
        verify(receiptService).confirmStale(second.getId());
    }

    @Test
    void sweep_usesCutoffOlderThanGraceWindow() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, true, 6);
        var cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(receiptRepository.findByStatusAndUpdatedAtBefore(
                eq(ReceiptStatus.PENDING_CONFIRMATION), cutoffCaptor.capture()))
                .thenReturn(List.of());

        confirmer.sweep();

        assertTrue(cutoffCaptor.getValue().isBefore(LocalDateTime.now().minusHours(5)),
                "cutoff should be at least afterHours in the past");
    }

    @Test
    void sweep_oneFailingConfirmDoesNotStopTheBatch() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, true, 6);
        var failing = pendingReceipt();
        var healthy = pendingReceipt();
        when(receiptRepository.findByStatusAndUpdatedAtBefore(
                eq(ReceiptStatus.PENDING_CONFIRMATION), any(LocalDateTime.class)))
                .thenReturn(List.of(failing, healthy));
        doThrow(new RuntimeException("boom")).when(receiptService).confirmStale(failing.getId());

        confirmer.sweep();

        verify(receiptService).confirmStale(healthy.getId());
    }

    @Test
    void sweep_disabledDoesNothing() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, false, 6);

        confirmer.sweep();

        verifyNoInteractions(receiptRepository, receiptService);
    }
}
