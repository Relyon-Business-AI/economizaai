package com.relyon.economizaai.exception;

public class MerchantAccessAlreadyExistsException extends DomainException {

    public MerchantAccessAlreadyExistsException(String cnpjRoot) {
        super("merchant.access.already.exists", cnpjRoot);
    }
}
