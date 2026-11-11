package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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

    private static final String CONTINGENCY_CHAVE = "43250612345678000190650010000012349000012340";

    /** Sweeper with the generic device-fetch fallback OFF (default behavior). */
    private ProcessingReceiptSweeper offSweeper() {
        return sweeper(false, true);
    }

    /** Sweeper with the generic device-fetch fallback ON. */
    private ProcessingReceiptSweeper onSweeper() {
        return sweeper(true, true);
    }

    private ProcessingReceiptSweeper sweeper(boolean genericDeviceFetch, boolean importWorkerEnabled) {
        return new ProcessingReceiptSweeper(receiptRepository, receiptIngestionService,
                new PrefetchPolicy("PE", genericDeviceFetch), transactionTemplate, 10, 15, 1440, importWorkerEnabled);
    }

    /** A PROCESSING row with no stored QR — nothing to re-ingest, so it can only be failed. */
    private Receipt stuckReceipt() {
        return Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.PROCESSING)
                .origin(ReceiptOrigin.IMPORT).build();
    }

    private Receipt strandedScan() {
        return Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.PROCESSING)
                .origin(ReceiptOrigin.SCAN).qrPayload("https://sefaz/qr?p=abc").build();
    }

    private void stale(ReceiptStatus status, Receipt... receipts) {
        lenient().when(receiptRepository.findByStatusAndUpdatedAtBefore(eq(status), any(LocalDateTime.class)))
                .thenReturn(List.of(receipts));
    }

    private void conditionalFailWins(Receipt receipt) {
        when(receiptRepository.failIfStillStale(eq(receipt.getId()), any(), any(), eq(ReceiptStatus.FAILED_PARSE),
                any(), any())).thenReturn(1);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
    }

    // ── Flag OFF ─────────────────────────────────────────────────────────────

    @Test
    void sweep_failsStuckProcessingReceiptsWithAConditionalUpdate() {
        var first = stuckReceipt();
        var second = stuckReceipt();
        stale(ReceiptStatus.PROCESSING, first, second);
        conditionalFailWins(first);
        conditionalFailWins(second);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        verify(receiptRepository).failIfStillStale(eq(first.getId()), eq(ReceiptStatus.PROCESSING),
                any(LocalDateTime.class), eq(ReceiptStatus.FAILED_PARSE), eq("receipt.processing.timeout"), any());
        verify(receiptRepository).failIfStillStale(eq(second.getId()), eq(ReceiptStatus.PROCESSING),
                any(LocalDateTime.class), eq(ReceiptStatus.FAILED_PARSE), eq("receipt.processing.timeout"), any());
        // Never a blind entity save — that is what used to overwrite a just-finished ingest.
        verify(receiptRepository, never()).saveAll(any());
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void sweep_force_failedRows_notifyTheWaitingOwner() {
        var first = stuckReceipt();
        var second = stuckReceipt();
        stale(ReceiptStatus.PROCESSING, first, second);
        conditionalFailWins(first);
        conditionalFailWins(second);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        // A row stuck past the timeout means the owner has been waiting — ping each one.
        verify(receiptIngestionService).notifyFailedIfSlow(first);
        verify(receiptIngestionService).notifyFailedIfSlow(second);
    }

    @Test
    void sweep_receiptThatFinishedMeanwhile_isNeitherOverwrittenNorNotified() {
        var justFinished = stuckReceipt();
        stale(ReceiptStatus.PROCESSING, justFinished);
        // The ingest committed PENDING_CONFIRMATION after the sweeper read the row: the
        // conditional update matches nothing.
        when(receiptRepository.failIfStillStale(eq(justFinished.getId()), any(), any(), any(), any(), any()))
                .thenReturn(0);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        verify(receiptIngestionService, never()).notifyFailedIfSlow(any());
        verify(receiptRepository, never()).save(any());
        verify(receiptRepository, never()).saveAll(any());
    }

    @Test
    void sweep_strandedScan_isRequeuedOnceInsteadOfFailed() {
        var stranded = strandedScan();
        stale(ReceiptStatus.PROCESSING, stranded);
        when(receiptRepository.requeueStrandedOnce(eq(stranded.getId()), eq(ReceiptStatus.PROCESSING),
                any(LocalDateTime.class), eq(ReceiptStatus.IMPORT_QUEUED), eq(false), any())).thenReturn(1);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        verify(receiptRepository, never()).failIfStillStale(any(), any(), any(), any(), any(), any());
        verify(receiptIngestionService, never()).notifyFailedIfSlow(any());
    }

    @Test
    void sweep_strandedScanAlreadyRequeued_isFailedSoItCannotLoop() {
        var strandedAgain = strandedScan();
        strandedAgain.setStrandedRequeued(true);
        stale(ReceiptStatus.PROCESSING, strandedAgain);
        conditionalFailWins(strandedAgain);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        verify(receiptRepository, never()).requeueStrandedOnce(any(), any(), any(), any(), anyBoolean(), any());
        verify(receiptRepository).failIfStillStale(eq(strandedAgain.getId()), eq(ReceiptStatus.PROCESSING),
                any(LocalDateTime.class), eq(ReceiptStatus.FAILED_PARSE), eq("receipt.processing.timeout"), any());
    }

    @Test
    void sweep_strandedScan_requeueLostTheRace_fallsBackToTheConditionalFail() {
        var stranded = strandedScan();
        stale(ReceiptStatus.PROCESSING, stranded);
        when(receiptRepository.requeueStrandedOnce(eq(stranded.getId()), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(0);
        when(receiptRepository.failIfStillStale(eq(stranded.getId()), any(), any(), any(), any(), any()))
                .thenReturn(0);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        verify(receiptRepository).failIfStillStale(eq(stranded.getId()), any(), any(), any(), any(), any());
        verify(receiptIngestionService, never()).notifyFailedIfSlow(any());
    }

    @Test
    void sweep_strandedScan_workerDisabled_isFailedBecauseNothingWouldPickItUp() {
        var stranded = strandedScan();
        stale(ReceiptStatus.PROCESSING, stranded);
        conditionalFailWins(stranded);
        runTransactionCallbacksInline();

        sweeper(false, false).sweep();

        verify(receiptRepository, never()).requeueStrandedOnce(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void sweep_strandedContingencyResync_isRequeuedKeepingTheFreeOnlyRequest() {
        var stranded = strandedScan();
        stranded.setChaveAcesso(CONTINGENCY_CHAVE);
        stranded.setResyncingFromContingency(true);
        stale(ReceiptStatus.PROCESSING, stranded);
        when(receiptRepository.requeueStrandedOnce(eq(stranded.getId()), any(), any(), any(), eq(true), any()))
                .thenReturn(1);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        verify(receiptRepository).requeueStrandedOnce(eq(stranded.getId()), eq(ReceiptStatus.PROCESSING),
                any(LocalDateTime.class), eq(ReceiptStatus.IMPORT_QUEUED), eq(true), any());
    }

    @Test
    void sweep_flagOff_failsStrandedNeedsDeviceFetchReceipts() {
        var stranded = Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.NEEDS_DEVICE_FETCH)
                .qrPayload("https://sefaz/qr?p=abc").build();
        stale(ReceiptStatus.NEEDS_DEVICE_FETCH, stranded);
        conditionalFailWins(stranded);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        verify(receiptRepository).failIfStillStale(eq(stranded.getId()), eq(ReceiptStatus.NEEDS_DEVICE_FETCH),
                any(LocalDateTime.class), eq(ReceiptStatus.FAILED_PARSE), eq("receipt.device_fetch.timeout"), any());
        verify(receiptRepository, never()).requeueStrandedOnce(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void sweep_failsStrandedImportQueuedReceiptsWithHonestReason() {
        var stranded = Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.IMPORT_QUEUED)
                .qrPayload("https://sefaz/qr?p=abc").build();
        stale(ReceiptStatus.IMPORT_QUEUED, stranded);
        conditionalFailWins(stranded);
        runTransactionCallbacksInline();

        offSweeper().sweep();

        verify(receiptRepository).failIfStillStale(eq(stranded.getId()), eq(ReceiptStatus.IMPORT_QUEUED),
                any(LocalDateTime.class), eq(ReceiptStatus.FAILED_PARSE), eq("receipt.import_queued.timeout"), any());
        verify(receiptRepository, never()).requeueStrandedOnce(any(), any(), any(), any(), anyBoolean(), any());
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

        verify(receiptRepository, never()).failIfStillStale(any(), any(), any(), any(), any(), any());
        verify(receiptRepository, never()).requeueStrandedOnce(any(), any(), any(), any(), anyBoolean(), any());
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
        verify(receiptRepository, never()).failIfStillStale(any(), any(), any(), any(), any(), any());
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
