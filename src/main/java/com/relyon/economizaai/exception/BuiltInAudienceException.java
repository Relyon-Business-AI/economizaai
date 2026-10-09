package com.relyon.economizaai.exception;

public class BuiltInAudienceException extends DomainException {

    public BuiltInAudienceException() {
        super("audience.builtin.immutable");
    }
}
