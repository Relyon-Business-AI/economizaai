package com.relyon.economizai.service;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Auto-confirms receipts the user parsed but never manually confirmed. A large
 * share of users don't realize they must confirm, so their notas sit in
 * PENDING_CONFIRMATION contributing nothing — not the price index, not even their
 * own dashboard. After a grace window (default 6h) we confirm them for the user
 * (they can still edit/delete after; contribution still respects contributionOptIn).
 *
 * <p>A plain {@code @Scheduled} sweep — no cron infra needed. Each receipt is
 * confirmed in its own transaction ({@link ReceiptService#confirmStale}) so one
 * bad row can't roll back the batch, and the full downstream fan-out (canonicalize,
 * price index, savings) runs exactly as a manual confirm.
 */
@Slf4j
@Service
public class PendingReceiptAutoConfirmer {

    private final ReceiptRepository receiptRepository;
    private final ReceiptService receiptService;
    private final boolean enabled;
    private final int afterHours;

    public PendingReceiptAutoConfirmer(
            ReceiptRepository receiptRepository,
            ReceiptService receiptService,
            @Value("${economizai.receipts.auto-confirm.enabled:true}") boolean enabled,
            @Value("${economizai.receipts.auto-confirm.after-hours:6}") int afterHours) {
        this.receiptRepository = receiptRepository;
        this.receiptService = receiptService;
        this.enabled = enabled;
        this.afterHours = Math.max(1, afterHours);
    }

    @Scheduled(fixedDelayString = "${economizai.receipts.auto-confirm.sweeper-delay-ms:1800000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        var cutoff = LocalDateTime.now().minusHours(afterHours);
        var stale = receiptRepository.findByStatusAndCreatedAtBefore(ReceiptStatus.PENDING_CONFIRMATION, cutoff)
                .stream().map(Receipt::getId).toList();
        if (stale.isEmpty()) {
            return;
        }
        var confirmed = 0;
        for (var receiptId : stale) {
            try {
                receiptService.confirmStale(receiptId);
                confirmed++;
            } catch (RuntimeException ex) {
                log.warn("auto-confirm.failed receipt={} reason={}", abbrev(receiptId.toString()), ex.getMessage());
            }
        }
        log.info("auto-confirm.swept pending={} confirmed={} olderThanHours={}", stale.size(), confirmed, afterHours);
    }

    private static String abbrev(String id) {
        return id == null || id.length() < 8 ? id : id.substring(0, 8);
    }
}
