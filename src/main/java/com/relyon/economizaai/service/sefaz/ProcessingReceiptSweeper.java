package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Safety net for receipts stranded in PROCESSING. The async ingest normally
 * transitions every receipt to PENDING_CONFIRMATION or FAILED_PARSE, but three
 * paths can strand a row: an app restart with ingests queued/in-flight, a
 * failure the ingest couldn't record, or a task the pool rejected before the
 * dispatch-side guard existed. A stranded PROCESSING row makes the FE poll
 * forever — this sweep gives a stranded SCAN one second chance (re-queued into
 * the paced lane) and fails anything else older than the timeout, so the user
 * gets a terminal status and can rescan.
 *
 * <p>Every transition is a CONDITIONAL update ("still in that status, still
 * older than the cutoff"), one row per short transaction: an ingest that
 * finishes between the sweeper's read and its write is never overwritten.
 *
 * <p>The timeout must exceed the worst-case legitimate ingest (MS captcha
 * retries are wall-clock-bounded at 3 min + Infosimples fallback), so 10 min
 * by default.
 */
@Slf4j
@Service
public class ProcessingReceiptSweeper {

    private final ReceiptRepository receiptRepository;
    private final ReceiptIngestionService receiptIngestionService;
    private final PrefetchPolicy prefetchPolicy;
    private final TransactionTemplate transactionTemplate;
    private final int timeoutMinutes;
    private final int deviceFetchTimeoutMinutes;
    private final int importQueuedTimeoutMinutes;
    private final boolean importWorkerEnabled;

    public ProcessingReceiptSweeper(
            ReceiptRepository receiptRepository,
            ReceiptIngestionService receiptIngestionService,
            PrefetchPolicy prefetchPolicy,
            TransactionTemplate transactionTemplate,
            @Value("${economizaai.ingestion.processing-timeout-minutes:10}") int timeoutMinutes,
            @Value("${economizaai.ingestion.device-fetch-timeout-minutes:15}") int deviceFetchTimeoutMinutes,
            @Value("${economizaai.ingestion.import-queued-timeout-minutes:1440}") int importQueuedTimeoutMinutes,
            @Value("${economizaai.import.worker.enabled:true}") boolean importWorkerEnabled) {
        this.receiptRepository = receiptRepository;
        this.receiptIngestionService = receiptIngestionService;
        this.prefetchPolicy = prefetchPolicy;
        this.transactionTemplate = transactionTemplate;
        this.timeoutMinutes = Math.max(1, timeoutMinutes);
        this.deviceFetchTimeoutMinutes = Math.max(1, deviceFetchTimeoutMinutes);
        this.importQueuedTimeoutMinutes = Math.max(1, importQueuedTimeoutMinutes);
        this.importWorkerEnabled = importWorkerEnabled;
    }

    @Scheduled(fixedDelayString = "${economizaai.ingestion.sweeper-delay-ms:60000}")
    public void sweep() {
        sweepStale(ReceiptStatus.PROCESSING, timeoutMinutes, "receipt.processing.timeout", "stuck_processing");
        // A NEEDS_DEVICE_FETCH row waits for the app to re-post the nota it fetched
        // on-device; a capable app resolves it in seconds. With the generic device-fetch
        // fallback OFF, anything still stranded past the timeout will never resolve (app
        // closed, or an older app that can't fetch), so fail it so the user gets a terminal
        // state. With the fallback ON, sweepStaleDeviceFetchViaInfosimples handles these
        // instead — it tries the paid by-chave fallback before failing — so skip them here
        // to avoid double-processing.
        if (!prefetchPolicy.isGenericEnabled()) {
            sweepStale(ReceiptStatus.NEEDS_DEVICE_FETCH, deviceFetchTimeoutMinutes,
                    "receipt.device_fetch.timeout", "stuck_device_fetch");
        }
        // IMPORT_QUEUED rows drain slowly BY DESIGN (paced worker), so the timeout is
        // generous (24h default). But with the worker disabled — or a chave that stops
        // being reconsultable — they'd strand forever; fail them honestly instead.
        sweepStale(ReceiptStatus.IMPORT_QUEUED, importQueuedTimeoutMinutes,
                "receipt.import_queued.timeout", "stuck_import_queue");
    }

    /**
     * Generic device-fetch fallback (flag ON): a NEEDS_DEVICE_FETCH that the app couldn't
     * resolve within the timeout (complex portal, or the app was closed) tries the paid
     * Infosimples by-chave fallback before failing — "continue the fallbacks until one
     * resolves or it fails for good". Deliberately NOT {@code @Transactional}: it flips each
     * row to PROCESSING in a short tx, then dispatches the untransacted Infosimples ingest
     * AFTER commit (an outbound HTTP call must never sit inside a DB transaction). No-op when
     * the flag is OFF — the main sweep fails these rows as before.
     */
    @Scheduled(fixedDelayString = "${economizaai.ingestion.sweeper-delay-ms:60000}")
    public void sweepStaleDeviceFetchViaInfosimples() {
        if (!prefetchPolicy.isGenericEnabled()) return;
        var cutoff = LocalDateTime.now().minusMinutes(deviceFetchTimeoutMinutes);
        var stale = receiptRepository.findByStatusAndUpdatedAtBefore(ReceiptStatus.NEEDS_DEVICE_FETCH, cutoff);
        if (stale.isEmpty()) return;
        var requeued = 0;
        for (var receipt : stale) {
            var reference = requeueToProcessing(receipt.getId());
            if (reference == null) continue; // resolved/changed between the read and the flip
            try {
                receiptIngestionService.ingestInfosimplesOnly(reference.id(), reference.chave());
                requeued++;
            } catch (TaskRejectedException poolFull) {
                // The row is already committed PROCESSING; don't let it hang — fail it now.
                receiptIngestionService.markFailed(reference.id(), poolFull);
            }
        }
        if (requeued > 0) {
            log.info("sweep device_fetch_timeout.infosimples_requeue count={} olderThanMinutes={}",
                    requeued, deviceFetchTimeoutMinutes);
        }
    }

    /** Flips a still-stale NEEDS_DEVICE_FETCH row to PROCESSING in its own short tx; null if it changed. */
    private RequeueRef requeueToProcessing(UUID receiptId) {
        return transactionTemplate.execute(txStatus -> {
            var receipt = receiptRepository.findById(receiptId).orElse(null);
            if (receipt == null || receipt.getStatus() != ReceiptStatus.NEEDS_DEVICE_FETCH) {
                return null;
            }
            receipt.setStatus(ReceiptStatus.PROCESSING);
            receiptRepository.save(receipt);
            return new RequeueRef(receipt.getId(), receipt.getChaveAcesso());
        });
    }

    private record RequeueRef(UUID id, String chave) {}

    /**
     * Rows of {@code staleStatus} untouched for longer than the timeout: a stranded scan is
     * re-queued once, everything else is failed. Keyed on updatedAt, NOT createdAt: a RETRY
     * flips a weeks-old row back to PROCESSING, and by createdAt the sweeper would kill it
     * instantly (it did — 13 notas do primeiro retry-batch morreram assim). updatedAt is
     * bumped by the status flip, so the timeout counts from when processing actually began.
     */
    private void sweepStale(ReceiptStatus staleStatus, int olderThanMinutes, String reasonKey, String event) {
        var cutoff = LocalDateTime.now().minusMinutes(olderThanMinutes);
        var stale = receiptRepository.findByStatusAndUpdatedAtBefore(staleStatus, cutoff);
        if (stale.isEmpty()) return;
        var requeued = 0;
        var failed = 0;
        for (var candidate : stale) {
            if (deservesSecondChance(candidate) && requeueStrandedOnce(candidate, cutoff)) {
                requeued++;
            } else if (failIfStillStale(candidate.getId(), staleStatus, cutoff, reasonKey)) {
                failed++;
            }
        }
        if (requeued > 0 || failed > 0) {
            log.warn("sweep {} failed={} requeued={} olderThanMinutes={}", event, failed, requeued, olderThanMinutes);
        }
    }

    /**
     * A SCANNED nota still PROCESSING past the timeout was most likely just stranded (restart,
     * long wait in the queue) — its stored QR can simply be ingested again. Only when the paced
     * worker is on (it is what picks the re-queued row up) and only once per nota.
     */
    private boolean deservesSecondChance(Receipt candidate) {
        return importWorkerEnabled
                && candidate.getStatus() == ReceiptStatus.PROCESSING
                && candidate.getOrigin() == ReceiptOrigin.SCAN
                && !candidate.isStrandedRequeued()
                && candidate.getQrPayload() != null && !candidate.getQrPayload().isBlank();
    }

    /** True when this call moved the row back to the paced queue (it was still stale and never re-queued). */
    private boolean requeueStrandedOnce(Receipt candidate, LocalDateTime cutoff) {
        // A contingency resync attempt that got stranded keeps its free-only request.
        var freeOnly = candidate.isResyncingFromContingency()
                && ReceiptIngestionService.isRecoverableContingency(candidate.getChaveAcesso());
        var requeued = transactionTemplate.execute(txStatus -> receiptRepository.requeueStrandedOnce(
                candidate.getId(), ReceiptStatus.PROCESSING, cutoff, ReceiptStatus.IMPORT_QUEUED,
                freeOnly, LocalDateTime.now()));
        return requeued != null && requeued == 1;
    }

    /**
     * True when this call failed the row. The owner has been waiting past the timeout, so a
     * row that was really failed pings them (dispatched after the short tx commits).
     */
    private boolean failIfStillStale(UUID receiptId, ReceiptStatus staleStatus, LocalDateTime cutoff,
                                     String reasonKey) {
        var failed = transactionTemplate.execute(txStatus -> {
            var updatedRows = receiptRepository.failIfStillStale(receiptId, staleStatus, cutoff,
                    ReceiptStatus.FAILED_PARSE, reasonKey, LocalDateTime.now());
            if (updatedRows != 1) return false;
            receiptRepository.findById(receiptId).ifPresent(receiptIngestionService::notifyFailedIfSlow);
            return true;
        });
        return Boolean.TRUE.equals(failed);
    }
}
