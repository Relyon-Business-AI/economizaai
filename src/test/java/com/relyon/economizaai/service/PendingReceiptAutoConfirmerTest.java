package com.relyon.economizaai.service;

import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingReceiptAutoConfirmerTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptService receiptService;

    @Test
    void sweep_confirmsStalePendingReceiptsByUpdatedAt() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, true, 6, 50);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(receiptRepository.findAutoConfirmableIds(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        confirmer.sweep();

        verify(receiptService).confirmStale(first);
        verify(receiptService).confirmStale(second);
    }

    @Test
    void sweep_usesCutoffOlderThanGraceWindow() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, true, 6, 50);
        var cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(receiptRepository.findAutoConfirmableIds(cutoffCaptor.capture(), any(Pageable.class)))
                .thenReturn(List.of());

        confirmer.sweep();

        assertTrue(cutoffCaptor.getValue().isBefore(LocalDateTime.now().minusHours(5)),
                "cutoff should be at least afterHours in the past");
    }

    @Test
    void sweep_asksForAtMostTheConfiguredCapPerTick() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, true, 6, 7);
        var pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(receiptRepository.findAutoConfirmableIds(any(LocalDateTime.class), pageCaptor.capture()))
                .thenReturn(List.of());

        confirmer.sweep();

        assertEquals(PageRequest.of(0, 7), pageCaptor.getValue());
    }

    @Test
    void sweep_oneFailingConfirmDoesNotStopTheBatch() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, true, 6, 50);
        var failing = UUID.randomUUID();
        var healthy = UUID.randomUUID();
        when(receiptRepository.findAutoConfirmableIds(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(failing, healthy));
        doThrow(new RuntimeException("boom")).when(receiptService).confirmStale(failing);

        confirmer.sweep();

        verify(receiptService).confirmStale(healthy);
    }

    @Test
    void sweep_disabledDoesNothing() {
        var confirmer = new PendingReceiptAutoConfirmer(receiptRepository, receiptService, false, 6, 50);

        confirmer.sweep();

        verifyNoInteractions(receiptRepository, receiptService);
    }
}
