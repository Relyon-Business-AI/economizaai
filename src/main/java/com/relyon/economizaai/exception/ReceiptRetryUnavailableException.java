package com.relyon.economizaai.exception;

/**
 * "Tentar novamente" was requested for a nota that has NO retryable source:
 * not a scanned QR, not an RS-reconsultable chave, and no stored NFe XML to
 * re-parse (e.g. a photo receipt). Surfaced as a localized 4xx instead of the
 * old lying 202 that silently did nothing.
 */
public class ReceiptRetryUnavailableException extends DomainException {

    public ReceiptRetryUnavailableException() {
        super("receipt.retry.unavailable");
    }

    /**
     * The nota failed for a reason a retry cannot change (corrupted chave, nota not found at
     * the portal, unsupported merchant…) — {@code messageKey} tells the user what to do instead.
     */
    public ReceiptRetryUnavailableException(String messageKey) {
        super(messageKey);
    }
}
