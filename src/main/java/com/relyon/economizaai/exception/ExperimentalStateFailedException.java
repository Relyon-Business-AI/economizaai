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

    public ExperimentalStateFailedException(String state) {
        super("receipt.state.experimental_failed", state);
    }
}
