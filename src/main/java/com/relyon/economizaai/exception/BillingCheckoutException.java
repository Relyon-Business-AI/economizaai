package com.relyon.economizaai.exception;

public class BillingCheckoutException extends DomainException {

    public BillingCheckoutException() {
        super("billing.checkout.failed");
    }
}
