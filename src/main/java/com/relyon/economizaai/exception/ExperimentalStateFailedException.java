package com.relyon.economizaai.exception;

/**
 * Every layer of the experimental fallback chain (QR portal fetch + shared
 * parser, then Infosimples when enabled) failed for a state without a verified
 * adapter. The user sees "not supported yet, we're working on it"; the admin
 * inbox gets the evidence needed to build a dedicated adapter. Extends
 * {@link ReceiptParseException} so the ingest pipeline persists the raw HTML
 * on the FAILED_PARSE row when the failure happened at the parse stage.
 */
public class ExperimentalStateFailedException extends ReceiptParseException {

    /**
     * True when the chain failed specifically at a CAPTCHA wall the server couldn't
     * pass. A phone can't solve these either, so the ingest must NOT hand such a
     * receipt to device-fetch — it would just time out. IP-block/fetch failures
     * (captchaBlocked=false) are still device-fetch-eligible (e.g. PE).
     */
    private final boolean captchaBlocked;

    public ExperimentalStateFailedException(String state) {
        this(state, false);
    }

    public ExperimentalStateFailedException(String state, boolean captchaBlocked) {
        super("receipt.state.experimental_failed", state);
        this.captchaBlocked = captchaBlocked;
    }

    public boolean isCaptchaBlocked() {
        return captchaBlocked;
    }
}
