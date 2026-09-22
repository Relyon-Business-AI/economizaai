package com.relyon.economizaai.exception;

public class InvalidAuthTokenException extends DomainException {

    public InvalidAuthTokenException() {
        super("auth.token.invalid");
    }
}
