package com.relyon.economizaai.exception;

import com.relyon.economizaai.model.enums.InfosimplesOutcome;

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

    /**
     * True when the chain failed because the state's PORTAL/origin was momentarily UNREACHABLE
     * (Infosimples code 615 "origem indisponível", provider instability) rather than a per-nota
     * dead end (nota inexistente/cancelada). A transient, retry-later failure: the ingest parks the
     * receipt as {@link com.relyon.economizaai.model.enums.ReceiptStatus#PORTAL_PENDING} and the
     * recovery sweeper re-fetches it until the portal is back, instead of failing it outright.
     */
    private final boolean portalUnavailable;

    /**
     * How the paid Infosimples layer answered inside this chain, or {@code null} when it was not
     * tried (disabled, or deferred to an on-device fetch first). Lets the ingest avoid paying for
     * the same lookup again in the later device-fetch steps.
     */
    private final InfosimplesOutcome infosimplesOutcome;

    public ExperimentalStateFailedException(String state) {
        this(state, false, false);
    }

    public ExperimentalStateFailedException(String state, boolean captchaBlocked) {
        this(state, captchaBlocked, false);
    }

    public ExperimentalStateFailedException(String state, boolean captchaBlocked, boolean portalUnavailable) {
        this(state, captchaBlocked, portalUnavailable, null);
    }

    public ExperimentalStateFailedException(String state, boolean captchaBlocked, boolean portalUnavailable,
                                            InfosimplesOutcome infosimplesOutcome) {
        super("receipt.state.experimental_failed", state);
        this.captchaBlocked = captchaBlocked;
        this.portalUnavailable = portalUnavailable;
        this.infosimplesOutcome = infosimplesOutcome;
    }

    public InfosimplesOutcome getInfosimplesOutcome() {
        return infosimplesOutcome;
    }

    public boolean isCaptchaBlocked() {
        return captchaBlocked;
    }

    public boolean isPortalUnavailable() {
        return portalUnavailable;
    }
}
