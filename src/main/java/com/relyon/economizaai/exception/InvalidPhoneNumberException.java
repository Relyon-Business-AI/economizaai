package com.relyon.economizaai.exception;

public class InvalidPhoneNumberException extends DomainException {

    public InvalidPhoneNumberException() {
        super("user.phone.invalid");
    }
}
