package com.relyon.economizaai.exception;

public class ReceiptNotFoundException extends DomainException {

    public ReceiptNotFoundException() {
        super("receipt.not.found");
    }
}
