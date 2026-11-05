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
 * Re-fetches notas parked in {@link ReceiptStatus#PORTAL_PENDING} — the state's SEFAZ portal was
 * offline (origin unavailable / Infosimples 615) when the nota was scanned, so NO path could reach
 * it. The nota is fine; the portal just isn't answering, which can last minutes or weeks. Instead of
 * failing it, {@link ReceiptIngestionService} parks it here and this sweeper re-queues it for a fresh
 * fetch on a schedule (portal back → items appear → PENDING_CONFIRMATION and the owner gets a "nota
 * pronta" push; still down → stays PORTAL_PENDING). Past a long give-up window it becomes FAILED_PARSE.
 *
 * <p>Mirror of {@link ContingencyResyncSweeper}, but with a much longer window: a contingency note
 * only has ~72h to be transmitted, whereas a portal outage is out of everyone's hands and may take
 * weeks — we keep the nota alive instead of forcing a needless rescan.
 */
@Slf4j
@Service
public class PortalRecoverySweeper {

    private static final int BATCH = 200;

    private final ReceiptRepository receiptRepository;
    private final int windowHours;

    public PortalRecoverySweeper(
            ReceiptRepository receiptRepository,
            @Value("${economizaai.ingestion.portal-recovery-window-hours:336}") int windowHours) {
        this.receiptRepository = receiptRepository;
        this.windowHours = Math.max(1, windowHours);
    }

    @Scheduled(fixedDelayString = "${economizaai.ingestion.portal-recovery-delay-ms:21600000}")
    @Transactional
    public void resync() {
        var cutoff = LocalDateTime.now().minusHours(windowHours);
        giveUpExpired(cutoff);
        requeueWithinWindow(cutoff);
    }

    /** Past the window: the portal stayed down too long — terminal, honest "unavailable". */
    private void giveUpExpired(LocalDateTime cutoff) {
        var expired = receiptRepository.findByStatusAndCreatedAtBefore(
                ReceiptStatus.PORTAL_PENDING, cutoff, PageRequest.of(0, BATCH));
        if (expired.isEmpty()) return;
        expired.forEach(receipt -> {
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
            receipt.setParseErrorReason("receipt.portal.unavailable.expired:");
            receipt.setResyncingFromContingency(false);
        });
        receiptRepository.saveAll(expired);
        log.info("portal.recovery gave_up count={} windowHours={}", expired.size(), windowHours);
    }

    /** Within the window: re-queue for a fresh SEFAZ fetch via the paced import worker. */
    private void requeueWithinWindow(LocalDateTime cutoff) {
        var pending = receiptRepository.findByStatusAndCreatedAtAfter(
                ReceiptStatus.PORTAL_PENDING, cutoff, PageRequest.of(0, BATCH));
        var requeued = new ArrayList<Receipt>();
        for (var receipt : pending) {
            // Same guards as the manual retry: only QR scans with a stored payload can be re-fetched.
            if (receipt.getOrigin() != ReceiptOrigin.SCAN) continue;
            var qrPayload = receipt.getQrPayload();
            if (qrPayload == null || qrPayload.isBlank()) continue;
            receipt.setStatus(ReceiptStatus.IMPORT_QUEUED);
            // Mark it so the ingest success path pings the owner if this fetch recovers it.
            receipt.setResyncingFromContingency(true);
            requeued.add(receipt);
        }
        if (requeued.isEmpty()) return;
        receiptRepository.saveAll(requeued);
        log.info("portal.recovery requeued={} of pending={}", requeued.size(), pending.size());
    }
}
