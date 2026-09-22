package com.relyon.economizaai.exception;

public class InvalidProfilePictureException extends DomainException {

    public InvalidProfilePictureException(String reasonKey, String... arguments) {
        super(reasonKey, arguments);
    }
}
