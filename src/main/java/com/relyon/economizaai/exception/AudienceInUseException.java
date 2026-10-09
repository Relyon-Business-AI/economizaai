package com.relyon.economizaai.exception;

public class AudienceInUseException extends DomainException {

    public AudienceInUseException(String audienceName) {
        super("audience.in.use", audienceName);
    }
}
