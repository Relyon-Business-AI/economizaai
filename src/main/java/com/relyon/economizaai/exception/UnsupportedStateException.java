package com.relyon.economizaai.exception;

public class UnsupportedStateException extends DomainException {

    public UnsupportedStateException(String state) {
        super("receipt.state.unsupported", state);
    }
}
