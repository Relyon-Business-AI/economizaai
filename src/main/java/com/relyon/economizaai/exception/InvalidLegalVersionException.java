package com.relyon.economizaai.exception;

public class InvalidLegalVersionException extends DomainException {

    public InvalidLegalVersionException(String document, String submitted, String current) {
        super("legal.version.invalid", document, submitted, current);
    }
}
