package com.relyon.economizaai.exception;

public class InvalidReceiptPhotoException extends DomainException {

    public InvalidReceiptPhotoException(String messageKey, String... arguments) {
        super(messageKey, arguments);
    }
}
