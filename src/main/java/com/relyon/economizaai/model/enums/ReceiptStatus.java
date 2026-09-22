package com.relyon.economizaai.model.enums;

public enum ReceiptStatus {
    /**
     * Bulk-import backlog: created by the chave/CSV import, waiting for the paced
     * {@link com.relyon.economizaai.service.sefaz.ImportReconsultWorker} to reconsult it a
     * few at a time. Deliberately NOT {@link #PROCESSING} so the ProcessingReceiptSweeper
     * (which force-fails long-PROCESSING rows) never times out an import still in the queue.
     */
    IMPORT_QUEUED,
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
