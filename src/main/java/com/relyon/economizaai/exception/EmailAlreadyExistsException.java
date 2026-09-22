package com.relyon.economizaai.exception;

public class EmailAlreadyExistsException extends DomainException {

    public EmailAlreadyExistsException(String email) {
        super("user.email.already.exists", email);
    }
}
