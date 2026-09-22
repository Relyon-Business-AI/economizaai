package com.relyon.economizaai.exception;

public class GarimpoProviderNotConfiguredException extends DomainException {

    public GarimpoProviderNotConfiguredException(String providerKey) {
        super("garimpo.provider.not.configured", providerKey);
    }
}
