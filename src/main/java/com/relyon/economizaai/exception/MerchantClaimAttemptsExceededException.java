package com.relyon.economizaai.exception;

public class MerchantClaimAttemptsExceededException extends DomainException {

    public MerchantClaimAttemptsExceededException() {
        super("merchant.claim.code.attempts_exceeded");
    }
}
