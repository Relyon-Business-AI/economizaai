package com.relyon.economizaai.exception;

public class MerchantAccessNotFoundException extends DomainException {

    public MerchantAccessNotFoundException(String cnpjRoot) {
        super("merchant.access.not.found", cnpjRoot);
    }
}
