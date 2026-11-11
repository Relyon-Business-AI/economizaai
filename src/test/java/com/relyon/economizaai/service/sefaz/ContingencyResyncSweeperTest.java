package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContingencyResyncSweeperTest {

    private static final int WINDOW_HOURS = 72;
    private static final long RESYNC_DELAY_MS = 6 * 60 * 60 * 1000L;

    @Mock private ReceiptRepository receiptRepository;

    private ContingencyResyncSweeper sweeper() {
        return new ContingencyResyncSweeper(receiptRepository, WINDOW_HOURS, RESYNC_DELAY_MS);
    }

    private Receipt contingencyPending(int ageHours) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .status(ReceiptStatus.CONTINGENCY_PENDING)
                .origin(ReceiptOrigin.SCAN)
                .qrPayload("http://sefaz/qr")
                .build();
        receipt.setCreatedAt(LocalDateTime.now().minusHours(ageHours));
        return receipt;
    }

    private void pendingWithinWindow(Receipt... receipts) {
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.CONTINGENCY_PENDING), any(), any()))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndCreatedAtAfter(eq(ReceiptStatus.CONTINGENCY_PENDING), any(), any()))
                .thenReturn(List.of(receipts));
    }

    @Test
    void earlyAttempts_areRequeuedFreeOnly() {
        var receipt = contingencyPending(12);
        pendingWithinWindow(receipt);

        sweeper().resync();

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.IMPORT_QUEUED);
        assertThat(receipt.isResyncingFromContingency()).isTrue();
        assertThat(receipt.isResyncFreeOnly()).isTrue();
        assertThat(receipt.isContingencyPaidAttempted()).isFalse();
    }

    @Test
    void lastAttemptBeforeTheDeadline_allowsThePaidChainOnce() {
        var receipt = contingencyPending(WINDOW_HOURS - 3);
        pendingWithinWindow(receipt);

        sweeper().resync();

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.IMPORT_QUEUED);
        assertThat(receipt.isResyncFreeOnly()).isFalse();
        assertThat(receipt.isContingencyPaidAttempted()).isTrue();
    }

    @Test
    void paidAttemptAlreadyGranted_staysFreeOnlyEvenInsideTheLastWindow() {
        var receipt = contingencyPending(WINDOW_HOURS - 1);
        receipt.setContingencyPaidAttempted(true);
        pendingWithinWindow(receipt);

        sweeper().resync();

        assertThat(receipt.isResyncFreeOnly()).isTrue();
        assertThat(receipt.isContingencyPaidAttempted()).isTrue();
    }

    @Test
    void expiredNote_isFailedAndLeavesTheResyncLoop() {
        var expired = contingencyPending(WINDOW_HOURS + 1);
        expired.setResyncFreeOnly(true);
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.CONTINGENCY_PENDING), any(), any()))
                .thenReturn(List.of(expired));
        when(receiptRepository.findByStatusAndCreatedAtAfter(eq(ReceiptStatus.CONTINGENCY_PENDING), any(), any()))
                .thenReturn(List.of());

        sweeper().resync();

        assertThat(expired.getStatus()).isEqualTo(ReceiptStatus.FAILED_PARSE);
        assertThat(expired.getParseErrorReason()).isEqualTo("receipt.contingency.unavailable:");
        assertThat(expired.isResyncFreeOnly()).isFalse();
    }
}
