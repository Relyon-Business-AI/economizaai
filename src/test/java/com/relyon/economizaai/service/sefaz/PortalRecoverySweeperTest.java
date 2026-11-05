package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortalRecoverySweeperTest {

    @Mock private ReceiptRepository receiptRepository;

    private PortalRecoverySweeper sweeper() {
        return new PortalRecoverySweeper(receiptRepository, 336);
    }

    private Receipt portalPending(boolean scan, String qr) {
        return Receipt.builder()
                .id(UUID.randomUUID())
                .status(ReceiptStatus.PORTAL_PENDING)
                .origin(scan ? ReceiptOrigin.SCAN : ReceiptOrigin.IMPORT)
                .qrPayload(qr)
                .build();
    }

    @Test
    void requeuesScanWithinWindow_asImportQueued_flaggedForRecoveryPing() {
        var receipt = portalPending(true, "http://sefaz/qr");
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.PORTAL_PENDING), any(), any()))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndCreatedAtAfter(eq(ReceiptStatus.PORTAL_PENDING), any(), any()))
                .thenReturn(List.of(receipt));

        sweeper().resync();

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.IMPORT_QUEUED);
        assertThat(receipt.isResyncingFromContingency()).isTrue();
    }

    @Test
    void skipsNonScanOrPayloadless() {
        var noPayload = portalPending(true, "  ");
        var notScan = portalPending(false, "http://sefaz/qr");
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.PORTAL_PENDING), any(), any()))
                .thenReturn(List.of());
        when(receiptRepository.findByStatusAndCreatedAtAfter(eq(ReceiptStatus.PORTAL_PENDING), any(), any()))
                .thenReturn(List.of(noPayload, notScan));

        sweeper().resync();

        assertThat(noPayload.getStatus()).isEqualTo(ReceiptStatus.PORTAL_PENDING);
        assertThat(notScan.getStatus()).isEqualTo(ReceiptStatus.PORTAL_PENDING);
    }

    @Test
    void givesUpPastWindow_asFailedParse() {
        var expired = portalPending(true, "http://sefaz/qr");
        expired.setResyncingFromContingency(true);
        when(receiptRepository.findByStatusAndCreatedAtBefore(eq(ReceiptStatus.PORTAL_PENDING), any(), any()))
                .thenReturn(List.of(expired));
        when(receiptRepository.findByStatusAndCreatedAtAfter(eq(ReceiptStatus.PORTAL_PENDING), any(), any()))
                .thenReturn(List.of());

        sweeper().resync();

        assertThat(expired.getStatus()).isEqualTo(ReceiptStatus.FAILED_PARSE);
        assertThat(expired.getParseErrorReason()).startsWith("receipt.portal.unavailable.expired");
        assertThat(expired.isResyncingFromContingency()).isFalse();
    }
}
