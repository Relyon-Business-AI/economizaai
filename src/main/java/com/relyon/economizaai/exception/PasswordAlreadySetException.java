package com.relyon.economizaai.exception;

public class PasswordAlreadySetException extends DomainException {

    public PasswordAlreadySetException() {
        super("user.password.already.set");
    }
}
