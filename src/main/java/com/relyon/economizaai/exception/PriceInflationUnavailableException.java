package com.relyon.economizaai.exception;

/** "Preços e inflação" does not exist for this user (the audience setting keeps it to admins). */
public class PriceInflationUnavailableException extends DomainException {

    public PriceInflationUnavailableException() {
        super("price.inflation.unavailable");
    }
}
