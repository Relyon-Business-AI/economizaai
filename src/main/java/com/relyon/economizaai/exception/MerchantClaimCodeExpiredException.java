package com.relyon.economizaai.exception;

public class MerchantClaimCodeExpiredException extends DomainException {

    public MerchantClaimCodeExpiredException() {
        super("merchant.claim.code.expired");
    }
}
