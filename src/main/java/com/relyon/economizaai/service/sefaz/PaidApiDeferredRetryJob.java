package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.ReceiptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * A nota blocked by a paid-API GUARD (per-user daily cap, global daily budget, open
 * breaker) isn't broken — it just hit today's limit. Instead of leaving it a dead end
 * ("tente amanhã" and the user never comes back), re-queue those failures once a day,
 * after the caps reset, through the paced admin-retry lane. Notas older than the
 * window are left alone (the user has likely moved on / rescanned).
 */
@Slf4j
@Service
public class PaidApiDeferredRetryJob {

    static final String PAID_API_REASON_PREFIX = "receipt.paid_api.";

    private final ReceiptRepository receiptRepository;
    private final ReceiptService receiptService;
    private final int maxAgeDays;

    public PaidApiDeferredRetryJob(ReceiptRepository receiptRepository,
                                   ReceiptService receiptService,
                                   @Value("${economizaai.ingestion.paid-api-retry.max-age-days:7}") int maxAgeDays) {
        this.receiptRepository = receiptRepository;
        this.receiptService = receiptService;
        this.maxAgeDays = Math.max(1, maxAgeDays);
    }

    @Scheduled(cron = "${economizaai.ingestion.paid-api-retry.cron:0 30 3 * * *}", zone = "America/Sao_Paulo")
    public void retryNotasBlockedByPaidApiGuards() {
        var cutoff = LocalDateTime.now().minusDays(maxAgeDays);
        var receiptIds = receiptRepository
                .findByStatusAndParseErrorReasonContainingIgnoreCase(ReceiptStatus.FAILED_PARSE, PAID_API_REASON_PREFIX)
                .stream()
                .filter(receipt -> receipt.getCreatedAt() != null && receipt.getCreatedAt().isAfter(cutoff))
                .map(Receipt::getId)
                .toList();
        if (receiptIds.isEmpty()) return;
        var queued = receiptService.adminRetryFailedScans(receiptIds);
        log.info("paid_api_retry.requeued matched={} queued={} maxAgeDays={}", receiptIds.size(), queued, maxAgeDays);
    }
}
