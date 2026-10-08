package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * Resyncs contingency NFC-e. A note emitted offline (tpEmis != 1) often has NO items on the SEFAZ
 * portal when scanned — the store has up to ~72h to transmit it, and the items appear once it does.
 * Instead of failing these forever, {@link ReceiptIngestionService} parks them in
 * {@link ReceiptStatus#CONTINGENCY_PENDING}; this sweeper, within the window, re-queues them for a
 * fresh fetch (items now present → PENDING_CONFIRMATION, still absent → back to CONTINGENCY_PENDING).
 * Past the window they become FAILED_PARSE — the store never transmitted the note.
 *
 * <p>The user sees a transparent "aguardando SEFAZ" state with NO manual retry button: the resync
 * is automatic, so a manual retry would just burn a fetch on a note SEFAZ still doesn't have.
 */
@Slf4j
@Service
public class ContingencyResyncSweeper {

    private static final int BATCH = 200;

    private final ReceiptRepository receiptRepository;
    private final int windowHours;

    public ContingencyResyncSweeper(
            ReceiptRepository receiptRepository,
            @Value("${economizaai.ingestion.contingency-window-hours:72}") int windowHours) {
        this.receiptRepository = receiptRepository;
        this.windowHours = Math.max(1, windowHours);
    }

    @Scheduled(fixedDelayString = "${economizaai.ingestion.contingency-resync-delay-ms:21600000}")
    @Transactional
    public void resync() {
        var cutoff = LocalDateTime.now().minusHours(windowHours);
        giveUpExpired(cutoff);
        requeueWithinWindow(cutoff);
    }

    /** Past the window: the store never transmitted the note — terminal, honest "unavailable". */
    private void giveUpExpired(LocalDateTime cutoff) {
        var expired = receiptRepository.findByStatusAndCreatedAtBefore(
                ReceiptStatus.CONTINGENCY_PENDING, cutoff, PageRequest.of(0, BATCH));
        if (expired.isEmpty()) return;
        expired.forEach(receipt -> {
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
            receipt.setParseErrorReason("receipt.contingency.unavailable:");
        });
        receiptRepository.saveAll(expired);
        log.info("contingency.resync gave_up count={} windowHours={}", expired.size(), windowHours);
    }

    /** Within the window: re-queue for a fresh SEFAZ fetch via the paced import worker. */
    private void requeueWithinWindow(LocalDateTime cutoff) {
        var pending = receiptRepository.findByStatusAndCreatedAtAfter(
                ReceiptStatus.CONTINGENCY_PENDING, cutoff, PageRequest.of(0, BATCH));
        var requeued = new ArrayList<Receipt>();
        for (var receipt : pending) {
            // Same guards as the manual retry: only QR scans with a stored payload can be re-fetched.
            if (receipt.getOrigin() != ReceiptOrigin.SCAN) continue;
            var qrPayload = receipt.getQrPayload();
            if (qrPayload == null || qrPayload.isBlank()) continue;
            receipt.setStatus(ReceiptStatus.IMPORT_QUEUED);
            requeued.add(receipt);
        }
        if (requeued.isEmpty()) return;
        receiptRepository.saveAll(requeued);
        log.info("contingency.resync requeued={} of pending={}", requeued.size(), pending.size());
    }
}
