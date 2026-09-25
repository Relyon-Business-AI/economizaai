package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptImportServiceTest {

    private static final String RS_CHAVE = "43260593015006005182651200000076311055456577"; // RS, model 65, valid DV
    private static final String RS_CHAVE_55 = "43260915436940001177550010445204901195759810"; // RS, model 55, valid DV
    private static final String SP_CHAVE = "35260593015006005182651200000076311055456575"; // SP (35), valid DV, NOT reconsultable

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptService receiptService;
    @Mock private MerchantSupportGate merchantSupportGate;
    @Mock private LocalizedMessageService localizedMessageService;
    @InjectMocks private ReceiptImportService service;

    private User user;
    private final UUID householdId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var household = Household.builder().id(householdId).build();
        user = User.builder().id(UUID.randomUUID()).household(household).build();
        lenient().when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var receipt = inv.getArgument(0, Receipt.class);
            if (receipt.getId() == null) receipt.setId(UUID.randomUUID());
            return receipt;
        });
    }

    @Test
    void queuesEligibleChaveAsImportQueuedWithoutDispatch() {
        when(merchantSupportGate.isKnownBlockedCnpj(any())).thenReturn(false);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(householdId, RS_CHAVE)).thenReturn(Optional.empty());

        var response = service.importChaves(user, List.of(RS_CHAVE));

        assertThat(response.queued()).isEqualTo(1);
        assertThat(response.rejected()).isZero();
        var saved = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ReceiptStatus.IMPORT_QUEUED);
        assertThat(saved.getValue().getOrigin()).isEqualTo(ReceiptOrigin.IMPORT);
    }

    @Test
    void replacesStaleFailedOnReimportInsteadOfRejecting() {
        when(merchantSupportGate.isKnownBlockedCnpj(any())).thenReturn(false);
        var stale = Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.FAILED_PARSE).build();
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(householdId, RS_CHAVE)).thenReturn(Optional.of(stale));

        var response = service.importChaves(user, List.of(RS_CHAVE));

        assertThat(response.queued()).isEqualTo(1);
        assertThat(response.rejected()).isZero();
        verify(receiptRepository).delete(stale);
    }

    @Test
    void rejectsSupportedFormatButUnsupportedState() {
        // Valid 44-digit chave with a good check digit, but UF=SP → not reconsultable (RS-only import).
        // Fail-fast at classify, before any queue/spend.
        var response = service.importChaves(user, List.of(SP_CHAVE));

        assertThat(response.queued()).isZero();
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.rejectedChaves().get(0).reason()).isEqualTo("receipt.import.unsupported");
        verify(receiptRepository, never()).save(any(Receipt.class));
    }

    @Test
    void stopsQueuingOnceMonthlyCapReached() {
        when(merchantSupportGate.isKnownBlockedCnpj(any())).thenReturn(false);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(eq(householdId), any())).thenReturn(Optional.empty());
        doThrow(new PaywallException("receipt.cap.monthly")).when(receiptService).enforceMonthlyReceiptCap(user);

        var response = service.importChaves(user, List.of(RS_CHAVE, RS_CHAVE_55));

        assertThat(response.queued()).isZero();
        assertThat(response.rejected()).isEqualTo(2);
        assertThat(response.rejectedChaves()).allSatisfy(rejected ->
                assertThat(rejected.reason()).isEqualTo("receipt.import.cap_reached"));
        verify(receiptRepository, never()).save(any(Receipt.class));
    }

    @Test
    void rejectsConfirmedDuplicate() {
        when(merchantSupportGate.isKnownBlockedCnpj(any())).thenReturn(false);
        var confirmed = Receipt.builder().id(UUID.randomUUID()).status(ReceiptStatus.CONFIRMED).build();
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(householdId, RS_CHAVE)).thenReturn(Optional.of(confirmed));
        when(localizedMessageService.translate("receipt.import.duplicate")).thenReturn("já está no histórico");

        var response = service.importChaves(user, List.of(RS_CHAVE));

        assertThat(response.queued()).isZero();
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.rejectedChaves().get(0).reason()).isEqualTo("receipt.import.duplicate");
        verify(receiptRepository, never()).delete(any(Receipt.class));
    }

    @Test
    void retryRequeuesFailedRsReceiptOfSameHousehold() {
        var receiptId = UUID.randomUUID();
        var failed = Receipt.builder()
                .id(receiptId)
                .household(user.getHousehold())
                .chaveAcesso(RS_CHAVE)
                .status(ReceiptStatus.FAILED_PARSE)
                .parseErrorReason("receipt.processing.timeout")
                .build();
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(failed));

        var requeued = service.retry(user, List.of(receiptId));

        assertThat(requeued).isEqualTo(1);
        assertThat(failed.getStatus()).isEqualTo(ReceiptStatus.IMPORT_QUEUED);
        assertThat(failed.getParseErrorReason()).isNull();
    }
}
