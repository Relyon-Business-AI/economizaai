package com.relyon.economizaai.exception;

public class PriceAlertNotFoundException extends DomainException {

    public PriceAlertNotFoundException() {
        super("pricealert.not.found");
    }
}
