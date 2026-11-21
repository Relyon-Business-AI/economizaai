package com.relyon.economizaai.exception;

/** A bulk receipt request (import, retry, confirm, delete) carries more receipts than the configured maximum. */
public class ReceiptBatchTooLargeException extends DomainException {

    public ReceiptBatchTooLargeException(int maxReceipts) {
        super("receipt.batch.too_large", String.valueOf(maxReceipts));
    }
}
