package com.relyon.economizaai.exception;

public class GarimpoSearchFailedException extends DomainException {

    public GarimpoSearchFailedException(String providerKey) {
        super("garimpo.search.failed", providerKey);
    }
}
