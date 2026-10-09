package com.relyon.economizaai.exception;

public class AudienceNameConflictException extends DomainException {

    public AudienceNameConflictException(String name) {
        super("audience.name.exists", name);
    }
}
