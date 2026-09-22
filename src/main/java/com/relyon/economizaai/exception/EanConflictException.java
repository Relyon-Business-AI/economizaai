package com.relyon.economizaai.exception;

public class EanConflictException extends DomainException {

    public EanConflictException(String ean) {
        super("product.ean.conflict", ean);
    }
}
