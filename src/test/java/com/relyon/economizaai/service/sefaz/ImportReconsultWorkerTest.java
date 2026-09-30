package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportReconsultWorkerTest {

    private static final String CHAVE = "43260412345678000190650010000123451123456780";

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptIngestionService receiptIngestionService;
    @Mock private ImportCompletionNotifier importCompletionNotifier;
    @Mock private TransactionTemplate transactionTemplate;

    private ImportReconsultWorker worker(boolean enabled) {
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
        return new ImportReconsultWorker(receiptRepository, receiptIngestionService,
                importCompletionNotifier, transactionTemplate, enabled, 4, 6);
    }

    @Test
    void tick_emptyQueue_stillRunsTheCompletionSweep() {
        // The bug this guards against: an early return on an empty batch skipped the
        // completion check forever, so the "import finished" ping never fired.
        when(receiptRepository.countByStatus(ReceiptStatus.PROCESSING)).thenReturn(0L);
        when(receiptRepository.findByStatusOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

        worker(true).processQueue();

        verify(importCompletionNotifier).notifyCompletedBatches();
        verify(receiptIngestionService, never()).ingestReconsult(any(), any());
    }

    @Test
    void tick_poolFull_skipsDispatchButStillSweeps() {
        when(receiptRepository.countByStatus(ReceiptStatus.PROCESSING)).thenReturn(6L);

        worker(true).processQueue();

        verify(importCompletionNotifier).notifyCompletedBatches();
        verify(receiptRepository, never()).findByStatusOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void tick_dispatchesQueuedNotaThenSweeps() {
        var user = User.builder().id(UUID.randomUUID()).email("test456@economizaai.app").build();
        var queued = Receipt.builder().user(user).chaveAcesso(CHAVE)
                .status(ReceiptStatus.IMPORT_QUEUED).build();
        queued.setId(UUID.randomUUID());
        when(receiptRepository.countByStatus(ReceiptStatus.PROCESSING)).thenReturn(0L);
        when(receiptRepository.findByStatusOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(queued));
        when(receiptRepository.findById(queued.getId())).thenReturn(Optional.of(queued));

        worker(true).processQueue();

        verify(receiptIngestionService).ingestReconsult(queued.getId(), CHAVE);
        verify(importCompletionNotifier).notifyCompletedBatches();
    }

    @Test
    void tick_disabledWorker_doesNothing() {
        worker(false).processQueue();

        verify(importCompletionNotifier, never()).notifyCompletedBatches();
        verify(receiptRepository, never()).countByStatus(any());
    }
}
