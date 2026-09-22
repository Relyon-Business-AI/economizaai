package com.relyon.economizaai.exception;

import lombok.Getter;

@Getter
public class DomainException extends RuntimeException {

    private final String messageKey;
    private final String[] arguments;

    public DomainException(String messageKey, String... arguments) {
        this.messageKey = messageKey;
        this.arguments = arguments;
    }
}
