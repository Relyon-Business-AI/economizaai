package com.relyon.economizaai.exception;

public class MerchantSubscriptionRequiredException extends DomainException {

    public MerchantSubscriptionRequiredException() {
        super("merchant.subscription.required");
    }
}
