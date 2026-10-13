package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessingReceiptSweeperTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptIngestionService receiptIngestionService;
    @Mock private TransactionTemplate transactionTemplate;

    /** Sweeper with the generic device-fetch fallback OFF (default behavior). */
    private ProcessingReceiptSweeper offSweeper() {
        return new ProcessingReceiptSweeper(receiptRepository, receiptIngestionService,
                new PrefetchPolicy("PE", false), transactionTemplate, 10, 15, 1440);
    }

    /** Sweeper with the generic device-fetch fallback ON. */
    private ProcessingReceiptSweeper onSweeper() {
        return new ProcessingReceiptSweeper(receiptRepository, receiptIngestionService,
                new PrefetchPolicy("PE", true), transactionTemplate, 10, 15, 1440);
    }

    private Receipt stuckReceipt() {
        return Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.PROCESSING).build();
    }

    // ── Flag OFF: unchanged behavior ─────────────────────────────────────────

    @Test
    void sweep_failsStuckProcessingReceipts() {
        var first = stuckReceipt();
        var second = stuckReceipt();
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of());

        offSweeper().sweep();

        assertEquals(ReceiptStatus.FAILED_PARSE, first.getStatus());
        assertEquals(ReceiptStatus.FAILED_PARSE, second.getStatus());
        assertEquals("receipt.processing.timeout", first.getParseErrorReason());
        verify(receiptRepository).saveAll(List.of(first, second));
    }

    @Test
    void sweep_flagOff_failsStrandedNeedsDeviceFetchReceipts() {
        var stranded = Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.NEEDS_DEVICE_FETCH).build();
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of(stranded));

        offSweeper().sweep();

        assertEquals(ReceiptStatus.FAILED_PARSE, stranded.getStatus());
        assertEquals("receipt.device_fetch.timeout", stranded.getParseErrorReason());
        verify(receiptRepository).saveAll(List.of(stranded));
    }

    @Test
    void sweep_failsStrandedImportQueuedReceiptsWithHonestReason() {
        var stranded = Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.IMPORT_QUEUED).build();
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.IMPORT_QUEUED), any(LocalDateTime.class)))
                .thenReturn(List.of(stranded));

        offSweeper().sweep();

        assertEquals(ReceiptStatus.FAILED_PARSE, stranded.getStatus());
        assertEquals("receipt.import_queued.timeout", stranded.getParseErrorReason());
        verify(receiptRepository).saveAll(List.of(stranded));
    }

    @Test
    void sweep_importQueuedCutoffIsGenerous() {
        var cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.IMPORT_QUEUED), cutoffCaptor.capture()))
                .thenReturn(List.of());

        offSweeper().sweep();

        assertTrue(cutoffCaptor.getValue().isBefore(LocalDateTime.now().minusHours(23)),
                "IMPORT_QUEUED must only be swept after the generous 24h window");
    }

    @Test
    void sweep_noopWhenNothingStuck() {
        when(receiptRepository.findByStatusAndUpdatedAtBefore(any(ReceiptStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        offSweeper().sweep();

        verify(receiptRepository, never()).saveAll(any());
    }

    @Test
    void sweep_usesCutoffOlderThanTimeout() {
        var cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.PROCESSING), cutoffCaptor.capture()))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of());

        offSweeper().sweep();

        assertTrue(cutoffCaptor.getValue().isBefore(LocalDateTime.now().minusMinutes(9)),
                "cutoff should be at least timeoutMinutes in the past");
    }

    // ── Flag ON: device-fetch timeout tries Infosimples before failing ───────

    @Test
    void sweep_flagOn_doesNotFailNeedsDeviceFetch_leavesItToInfosimplesPass() {
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.IMPORT_QUEUED), any(LocalDateTime.class)))
                .thenReturn(List.of());

        onSweeper().sweep();

        // With the fallback ON, sweep() must not even query/fail NEEDS_DEVICE_FETCH.
        verify(receiptRepository, never())
                .findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class));
        verify(receiptRepository, never()).saveAll(any());
    }

    @Test
    void sweepStaleDeviceFetch_flagOff_noop() {
        onSweeperFlagOffSweep();
    }

    private void onSweeperFlagOffSweep() {
        offSweeper().sweepStaleDeviceFetchViaInfosimples();
        verify(receiptRepository, never())
                .findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class));
        verify(receiptIngestionService, never()).ingestInfosimplesOnly(any(), any());
    }

    @Test
    void sweepStaleDeviceFetch_flagOn_requeuesStaleRowToInfosimples() {
        var chave = "43260412345678000190650010000123451123456780";
        var id = UUID.randomUUID();
        var stale = Receipt.builder().id(id).chaveAcesso(chave).status(ReceiptStatus.NEEDS_DEVICE_FETCH).build();
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of(stale));
        when(receiptRepository.findById(id)).thenReturn(Optional.of(stale));
        runTransactionCallbacksInline();

        onSweeper().sweepStaleDeviceFetchViaInfosimples();

        // Flipped to PROCESSING and dispatched the Infosimples-only ingest.
        assertEquals(ReceiptStatus.PROCESSING, stale.getStatus());
        verify(receiptRepository).save(stale);
        verify(receiptIngestionService).ingestInfosimplesOnly(id, chave);
    }

    @Test
    void sweepStaleDeviceFetch_flagOn_rowChangedBeforeFlip_skipsDispatch() {
        var id = UUID.randomUUID();
        var stale = Receipt.builder().id(id).status(ReceiptStatus.NEEDS_DEVICE_FETCH).build();
        // Between the read and the flip the app resolved it (now PENDING_CONFIRMATION).
        var resolved = Receipt.builder().id(id).status(ReceiptStatus.PENDING_CONFIRMATION).build();
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of(stale));
        when(receiptRepository.findById(id)).thenReturn(Optional.of(resolved));
        runTransactionCallbacksInline();

        onSweeper().sweepStaleDeviceFetchViaInfosimples();

        verify(receiptIngestionService, never()).ingestInfosimplesOnly(any(), any());
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void sweepStaleDeviceFetch_flagOn_poolRejected_marksFailed() {
        var chave = "43260412345678000190650010000123451123456780";
        var id = UUID.randomUUID();
        var stale = Receipt.builder().id(id).chaveAcesso(chave).status(ReceiptStatus.NEEDS_DEVICE_FETCH).build();
        when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(ReceiptStatus.NEEDS_DEVICE_FETCH), any(LocalDateTime.class)))
                .thenReturn(List.of(stale));
        when(receiptRepository.findById(id)).thenReturn(Optional.of(stale));
        runTransactionCallbacksInline();
        doThrow(new TaskRejectedException("pool full")).when(receiptIngestionService).ingestInfosimplesOnly(id, chave);

        onSweeper().sweepStaleDeviceFetchViaInfosimples();

        // The committed PROCESSING row must not hang — fail it now.
        verify(receiptIngestionService).markFailed(eq(id), any(TaskRejectedException.class));
    }

    @SuppressWarnings("unchecked")
    private void runTransactionCallbacksInline() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }
}
