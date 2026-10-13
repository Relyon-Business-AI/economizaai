package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Safety net for receipts stranded in PROCESSING. The async ingest normally
 * transitions every receipt to PENDING_CONFIRMATION or FAILED_PARSE, but three
 * paths can strand a row: an app restart with ingests queued/in-flight, a
 * failure the ingest couldn't record, or a task the pool rejected before the
 * dispatch-side guard existed. A stranded PROCESSING row makes the FE poll
 * forever — this sweep fails anything older than the timeout so the user gets
 * a terminal status and can rescan.
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

    public ProcessingReceiptSweeper(
            ReceiptRepository receiptRepository,
            ReceiptIngestionService receiptIngestionService,
            PrefetchPolicy prefetchPolicy,
            TransactionTemplate transactionTemplate,
            @Value("${economizaai.ingestion.processing-timeout-minutes:10}") int timeoutMinutes,
            @Value("${economizaai.ingestion.device-fetch-timeout-minutes:15}") int deviceFetchTimeoutMinutes,
            @Value("${economizaai.ingestion.import-queued-timeout-minutes:1440}") int importQueuedTimeoutMinutes) {
        this.receiptRepository = receiptRepository;
        this.receiptIngestionService = receiptIngestionService;
        this.prefetchPolicy = prefetchPolicy;
        this.transactionTemplate = transactionTemplate;
        this.timeoutMinutes = Math.max(1, timeoutMinutes);
        this.deviceFetchTimeoutMinutes = Math.max(1, deviceFetchTimeoutMinutes);
        this.importQueuedTimeoutMinutes = Math.max(1, importQueuedTimeoutMinutes);
    }

    @Scheduled(fixedDelayString = "${economizaai.ingestion.sweeper-delay-ms:60000}")
    @Transactional
    public void sweep() {
        failStale(ReceiptStatus.PROCESSING, timeoutMinutes, "receipt.processing.timeout", "stuck_processing");
        // A NEEDS_DEVICE_FETCH row waits for the app to re-post the nota it fetched
        // on-device; a capable app resolves it in seconds. With the generic device-fetch
        // fallback OFF, anything still stranded past the timeout will never resolve (app
        // closed, or an older app that can't fetch), so fail it so the user gets a terminal
        // state. With the fallback ON, sweepStaleDeviceFetchViaInfosimples handles these
        // instead — it tries the paid by-chave fallback before failing — so skip them here
        // to avoid double-processing.
        if (!prefetchPolicy.isGenericEnabled()) {
            failStale(ReceiptStatus.NEEDS_DEVICE_FETCH, deviceFetchTimeoutMinutes,
                    "receipt.device_fetch.timeout", "stuck_device_fetch");
        }
        // IMPORT_QUEUED rows drain slowly BY DESIGN (paced worker), so the timeout is
        // generous (24h default). But with the worker disabled — or a chave that stops
        // being reconsultable — they'd strand forever; fail them honestly instead.
        failStale(ReceiptStatus.IMPORT_QUEUED, importQueuedTimeoutMinutes,
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

    private void failStale(ReceiptStatus status, int olderThanMinutes, String reasonKey, String event) {
        var cutoff = LocalDateTime.now().minusMinutes(olderThanMinutes);
        // Keyed on updatedAt, NOT createdAt: a RETRY flips a weeks-old row back to
        // PROCESSING, and by createdAt the sweeper would kill it instantly (it did —
        // 13 notas do primeiro retry-batch morreram assim). updatedAt is bumped by
        // the status flip, so the timeout counts from when processing actually began.
        var stuck = receiptRepository.findByStatusAndUpdatedAtBefore(status, cutoff);
        if (stuck.isEmpty()) return;
        stuck.forEach(receipt -> {
            receipt.setParseErrorReason(reasonKey);
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
        });
        receiptRepository.saveAll(stuck);
        log.warn("sweep {} failed={} olderThanMinutes={}", event, stuck.size(), olderThanMinutes);
    }
}
