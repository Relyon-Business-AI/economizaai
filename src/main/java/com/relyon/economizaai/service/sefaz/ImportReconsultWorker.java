package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Paces the bulk-import backlog. The import persists chaves as
 * {@link ReceiptStatus#IMPORT_QUEUED} instead of flooding the shared ingest pool;
 * this worker flips a few at a time to PROCESSING and dispatches the reconsult, so
 * a 50-nota import trickles through instead of overwhelming the pool and getting
 * force-failed by the ProcessingReceiptSweeper ({@code receipt.processing.timeout}).
 *
 * <p>It yields to real scans: it only tops up to {@code max-in-flight} total
 * PROCESSING rows, so live receipt uploads always have pool headroom. A row is
 * PROCESSING only for the duration of its own reconsult (seconds), so the sweeper
 * never times an import out while it waits in the queue.
 */
@Slf4j
@Service
public class ImportReconsultWorker {

    private final ReceiptRepository receiptRepository;
    private final ReceiptIngestionService receiptIngestionService;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int batchSize;
    private final int maxInFlight;

    public ImportReconsultWorker(ReceiptRepository receiptRepository,
                                 ReceiptIngestionService receiptIngestionService,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${economizaai.import.worker.enabled:true}") boolean enabled,
                                 @Value("${economizaai.import.worker.batch-size:4}") int batchSize,
                                 @Value("${economizaai.import.worker.max-in-flight:6}") int maxInFlight) {
        this.receiptRepository = receiptRepository;
        this.receiptIngestionService = receiptIngestionService;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxInFlight = Math.max(1, maxInFlight);
    }

    @Scheduled(fixedDelayString = "${economizaai.import.worker.delay-ms:8000}")
    public void processQueue() {
        if (!enabled) return;
        var inFlight = receiptRepository.countByStatus(ReceiptStatus.PROCESSING);
        var slots = (int) (maxInFlight - inFlight);
        if (slots <= 0) return;
        var batch = receiptRepository.findByStatusOrderByCreatedAtAsc(
                ReceiptStatus.IMPORT_QUEUED, PageRequest.of(0, Math.min(batchSize, slots)));
        if (batch.isEmpty()) return;

        var dispatched = 0;
        for (var queued : batch) {
            var receiptId = queued.getId();
            var chave = queued.getChaveAcesso();
            var flipped = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                var receipt = receiptRepository.findById(receiptId).orElse(null);
                if (receipt == null || receipt.getStatus() != ReceiptStatus.IMPORT_QUEUED) return false;
                receipt.setStatus(ReceiptStatus.PROCESSING);
                receiptRepository.save(receipt);
                return true;
            }));
            if (!flipped) continue;
            try {
                receiptIngestionService.ingestReconsult(receiptId, chave);
                dispatched++;
            } catch (RuntimeException ex) {
                receiptIngestionService.markFailed(receiptId, ex);
            }
        }
        if (dispatched > 0) {
            log.info("import.worker dispatched={} inFlightBefore={}", dispatched, inFlight);
        }
    }
}
