package com.relyon.economizaai.exception;

public class MerchantClaimAlreadyOpenException extends DomainException {

    public MerchantClaimAlreadyOpenException(String cnpjRoot) {
        super("merchant.claim.already.open", cnpjRoot);
    }
}
