package com.relyon.economizaai.exception;

public class MerchantClaimAlreadyResolvedException extends DomainException {

    public MerchantClaimAlreadyResolvedException() {
        super("merchant.claim.already.resolved");
    }
}
