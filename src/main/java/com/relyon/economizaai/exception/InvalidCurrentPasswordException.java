package com.relyon.economizaai.exception;

public class InvalidCurrentPasswordException extends DomainException {

    public InvalidCurrentPasswordException() {
        super("user.password.invalid.current");
    }
}
