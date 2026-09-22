package com.relyon.economizaai.exception;

public class InvalidConsentRequestException extends DomainException {

    public InvalidConsentRequestException(String reason) {
        super("consent.invalid", reason);
    }
}
