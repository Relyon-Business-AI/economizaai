package com.relyon.economizaai.exception;

public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("auth.invalid.credentials");
    }
}
