package com.relyon.economizaai.model.enums;

public enum ReceiptStatus {
    /** Submitted; SEFAZ fetch + parse (incl. captcha solve) running in the background. */
    PROCESSING,
    /**
     * The server couldn't reach the state's portal (no verified adapter + the portal
     * blocks our datacenter IP, e.g. Pernambuco). The user's own device might — the app
     * fetches the nota's page on-device and reposts it to {@code /receipts/{id}/device-content}.
     * A recoverable, self-healing failure — NOT a dead end like {@link #FAILED_PARSE}.
     */
    NEEDS_DEVICE_FETCH,
    PENDING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    FAILED_PARSE
}
