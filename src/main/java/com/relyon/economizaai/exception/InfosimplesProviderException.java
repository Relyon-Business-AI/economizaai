package com.relyon.economizaai.exception;

/**
 * Infosimples answered with a NON-deterministic error — the provider/portal was
 * momentarily unstable (e.g. 609), not a per-nota verdict. Counts toward the
 * circuit breaker and tells the user it's transient ("tente de novo mais tarde").
 * Carries the provider result code so the paid-API ledger can record WHY it failed.
 */
public class InfosimplesProviderException extends ReceiptParseException {

    /** "Origem indisponível": the state's SEFAZ portal is offline, so the provider can't reach it either. */
    public static final int ORIGIN_UNAVAILABLE_CODE = 615;

    private final int code;

    public InfosimplesProviderException(int code) {
        super("infosimples.error");
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** The nota is fine and the portal is just offline: worth parking and re-fetching, not failing. */
    public boolean isOriginUnavailable() {
        return code == ORIGIN_UNAVAILABLE_CODE;
    }
}
