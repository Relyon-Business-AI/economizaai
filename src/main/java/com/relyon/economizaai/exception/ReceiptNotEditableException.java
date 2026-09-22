package com.relyon.economizaai.exception;

public class ReceiptNotEditableException extends DomainException {

    public ReceiptNotEditableException(String currentStatus) {
        super("receipt.not.editable", currentStatus);
    }
}
