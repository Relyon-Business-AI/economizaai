package com.relyon.economizaai.exception;

/**
 * Infosimples answered with a NON-deterministic error — the provider/portal was
 * momentarily unstable (e.g. 609), not a per-nota verdict. Counts toward the
 * circuit breaker and tells the user it's transient ("tente de novo mais tarde").
 * Carries the provider result code so the paid-API ledger can record WHY it failed.
 */
public class InfosimplesProviderException extends ReceiptParseException {

    private final int code;

    public InfosimplesProviderException(int code) {
        super("infosimples.error");
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
