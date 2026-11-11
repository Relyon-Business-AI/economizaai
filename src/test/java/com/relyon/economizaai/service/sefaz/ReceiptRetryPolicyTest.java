package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptRetryPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "receipt.parse.failed:chave-corrupted, receipt.retry.refused.chave",
            "receipt.chave.typo:, receipt.retry.refused.chave",
            "receipt.parse.failed:infosimples.not-found, receipt.retry.refused.not_found",
            "receipt.contingency.unavailable:, receipt.retry.refused.not_transmitted",
            "receipt.merchant.unsupported:, receipt.retry.refused.merchant",
            "receipt.qr.invalid:, receipt.retry.refused.qr",
            "receipt.state.unsupported:AC, receipt.retry.refused.state",
            "receipt.manual-chave.unsupported:SP, receipt.retry.refused.manual_chave",
            "receipt.manual-chave.nfe-unsupported:SP, receipt.retry.refused.manual_chave"
    })
    void deterministicFailures_areRefusedWithTheirOwnMessage(String parseErrorReason, String expectedMessageKey) {
        assertEquals(Optional.of(expectedMessageKey), ReceiptRetryPolicy.refusalMessageKey(parseErrorReason));
        assertTrue(ReceiptRetryPolicy.isDeterministicFailure(parseErrorReason));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "receipt.sefaz.fetch.failed:RS",
            "receipt.parse.failed:no-items-found",
            "receipt.parse.failed:infosimples.error",
            "receipt.processing.timeout",
            "receipt.paid_api.circuit_open:INFOSIMPLES",
            "receipt.paid_api.quota_exceeded:INFOSIMPLES",
            "receipt.captcha.failed:turnstile-rejected",
            "receipt.state.experimental_failed:PA",
            "receipt.portal.unavailable.expired:",
            "receipt.contingency.pending:227"
    })
    void transientOrFixableFailures_stayRetryable(String parseErrorReason) {
        assertFalse(ReceiptRetryPolicy.isDeterministicFailure(parseErrorReason));
    }

    @Test
    void missingReason_staysRetryable() {
        assertFalse(ReceiptRetryPolicy.isDeterministicFailure(null));
        assertFalse(ReceiptRetryPolicy.isDeterministicFailure(" "));
    }
}
