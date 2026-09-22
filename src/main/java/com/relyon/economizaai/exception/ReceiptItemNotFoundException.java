package com.relyon.economizaai.exception;

public class ReceiptItemNotFoundException extends DomainException {

    public ReceiptItemNotFoundException() {
        super("receipt.item.not.found");
    }
}
