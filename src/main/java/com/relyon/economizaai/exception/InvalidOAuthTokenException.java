package com.relyon.economizaai.exception;

public class InvalidOAuthTokenException extends DomainException {

    public InvalidOAuthTokenException() {
        super("auth.oauth.invalid");
    }
}
