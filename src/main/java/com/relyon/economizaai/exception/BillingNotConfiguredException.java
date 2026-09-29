package com.relyon.economizaai.exception;

public class BillingNotConfiguredException extends DomainException {

    public BillingNotConfiguredException() {
        super("billing.not.configured");
    }
}
