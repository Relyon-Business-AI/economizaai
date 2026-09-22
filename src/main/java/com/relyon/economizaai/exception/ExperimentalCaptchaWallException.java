package com.relyon.economizaai.exception;

/**
 * An experimental state's portal is captcha-gated and the best-effort solve
 * either wasn't possible (no solver / unsupported captcha type) or the portal
 * rejected the resubmitted token. Extends {@link CaptchaUnavailableException}
 * so the chain treats it as rescuable-by-Infosimples and no captcha cost is
 * double-recorded, while carrying the evidence a dedicated adapter needs.
 */
public class ExperimentalCaptchaWallException extends CaptchaUnavailableException
        implements ExperimentalPortalEvidence {

    private final String evidence;

    public ExperimentalCaptchaWallException(String state, String captchaType, String siteKey, String bodySnippet) {
        super(state);
        this.evidence = "captcha wall: type=" + captchaType
                + " sitekey=" + (siteKey == null ? "(not found)" : siteKey)
                + (bodySnippet == null ? "" : "\nportal body (first chars):\n" + bodySnippet);
    }

    @Override
    public String portalEvidence() {
        return evidence;
    }
}
