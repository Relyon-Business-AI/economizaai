package com.relyon.economizaai.exception;

public class InvalidPhoneVerificationException extends DomainException {

    public InvalidPhoneVerificationException() {
        super("user.phone.verification.invalid");
    }
}
