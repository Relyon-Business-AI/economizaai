package com.relyon.economizaai.exception;

public class GarimpoMarketplaceNotFoundException extends DomainException {

    public GarimpoMarketplaceNotFoundException(String providerKey) {
        super("garimpo.marketplace.not.found", providerKey);
    }
}
