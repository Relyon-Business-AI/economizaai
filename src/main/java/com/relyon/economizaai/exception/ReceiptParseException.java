package com.relyon.economizaai.exception;

public class ReceiptParseException extends DomainException {

    public ReceiptParseException(String reason) {
        super("receipt.parse.failed", reason);
    }

    /** For subclasses that carry their own localizable key (same FAILED_PARSE handling). */
    protected ReceiptParseException(String messageKey, String... arguments) {
        super(messageKey, arguments);
    }

    /** The specific "parser reached the DANFE but found no line items" failure. */
    public boolean isNoItemsFound() {
        var arguments = getArguments();
        return arguments.length > 0 && "no-items-found".equals(arguments[0]);
    }
}
