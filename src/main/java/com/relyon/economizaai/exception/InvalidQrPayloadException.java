package com.relyon.economizaai.exception;

public class InvalidQrPayloadException extends DomainException {

    public InvalidQrPayloadException() {
        super("receipt.qr.invalid");
    }
}
