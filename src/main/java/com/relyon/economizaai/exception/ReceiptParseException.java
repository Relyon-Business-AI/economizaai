package com.relyon.economizaai.exception;

public class ReceiptParseException extends DomainException {

    public ReceiptParseException(String reason) {
        super("receipt.parse.failed", reason);
    }

    /** For subclasses that carry their own localizable key (same FAILED_PARSE handling). */
    protected ReceiptParseException(String messageKey, String... arguments) {
        super(messageKey, arguments);
    }
}
