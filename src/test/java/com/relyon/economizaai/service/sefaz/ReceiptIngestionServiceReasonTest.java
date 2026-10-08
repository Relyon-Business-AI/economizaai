package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ReceiptParseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptIngestionServiceReasonTest {

    // Real SP NFC-e chave emitted in contingency (tpEmis = 9 at position 35 / index 34).
    private static final String CONTINGENCY_CHAVE = "35261005919026000151650030000759299353073726";
    // Same chave flipped to normal emission (tpEmis = 1).
    private static final String NORMAL_CHAVE =
            CONTINGENCY_CHAVE.substring(0, 34) + "1" + CONTINGENCY_CHAVE.substring(35);

    @Test
    void reclassifiesNoItemsOnContingencyNoteToContingencyKey() {
        var reason = ReceiptIngestionService.parseFailureReason(
                new ReceiptParseException("no-items-found"), CONTINGENCY_CHAVE);
        assertThat(reason).isEqualTo("receipt.contingency.pending:");
    }

    @Test
    void keepsGenericNoItemsReasonForNormalNote() {
        var reason = ReceiptIngestionService.parseFailureReason(
                new ReceiptParseException("no-items-found"), NORMAL_CHAVE);
        assertThat(reason).isEqualTo("receipt.parse.failed:no-items-found");
    }

    @Test
    void doesNotReclassifyOtherFailuresOnContingencyNote() {
        var reason = ReceiptIngestionService.parseFailureReason(
                new ReceiptParseException("bad-chave"), CONTINGENCY_CHAVE);
        assertThat(reason).isEqualTo("receipt.parse.failed:bad-chave");
    }
}
