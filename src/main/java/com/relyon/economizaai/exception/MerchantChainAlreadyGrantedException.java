package com.relyon.economizaai.exception;

public class MerchantChainAlreadyGrantedException extends DomainException {

    public MerchantChainAlreadyGrantedException(String cnpjRoot) {
        super("merchant.claim.already.granted", cnpjRoot);
    }
}
