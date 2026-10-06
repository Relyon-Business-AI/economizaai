package com.relyon.economizaai.exception;

public class MerchantClaimNotFoundException extends DomainException {

    public MerchantClaimNotFoundException() {
        super("merchant.claim.not.found");
    }
}
