package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
    private final int timeoutMinutes;
    private final int deviceFetchTimeoutMinutes;

    public ProcessingReceiptSweeper(
            ReceiptRepository receiptRepository,
            @Value("${economizaai.ingestion.processing-timeout-minutes:10}") int timeoutMinutes,
            @Value("${economizaai.ingestion.device-fetch-timeout-minutes:15}") int deviceFetchTimeoutMinutes) {
        this.receiptRepository = receiptRepository;
        this.timeoutMinutes = Math.max(1, timeoutMinutes);
        this.deviceFetchTimeoutMinutes = Math.max(1, deviceFetchTimeoutMinutes);
    }

    @Scheduled(fixedDelayString = "${economizaai.ingestion.sweeper-delay-ms:60000}")
    @Transactional
    public void sweep() {
        failStale(ReceiptStatus.PROCESSING, timeoutMinutes, "receipt.processing.timeout", "stuck_processing");
        // A NEEDS_DEVICE_FETCH row waits for the app to re-post the nota it fetched
        // on-device; a capable app resolves it in seconds. Anything still stranded past
        // the timeout will never resolve (app closed, or an older app that can't fetch —
        // and can't even render the status), so fail it so the user gets a terminal state.
        failStale(ReceiptStatus.NEEDS_DEVICE_FETCH, deviceFetchTimeoutMinutes,
                "receipt.device_fetch.timeout", "stuck_device_fetch");
    }

    private void failStale(ReceiptStatus status, int olderThanMinutes, String reasonKey, String event) {
        var cutoff = LocalDateTime.now().minusMinutes(olderThanMinutes);
        var stuck = receiptRepository.findByStatusAndCreatedAtBefore(status, cutoff);
        if (stuck.isEmpty()) return;
        stuck.forEach(receipt -> {
            receipt.setParseErrorReason(reasonKey);
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
        });
        receiptRepository.saveAll(stuck);
        log.warn("sweep {} failed={} olderThanMinutes={}", event, stuck.size(), olderThanMinutes);
    }
}
