package com.relyon.economizaai.exception;

public class InvalidProductDeletionException extends DomainException {

    public InvalidProductDeletionException(long receiptItemCount) {
        super("product.deletion.referenced", String.valueOf(receiptItemCount));
    }
}
