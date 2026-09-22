package com.relyon.economizaai.exception;

public class ProductNotFoundException extends DomainException {

    public ProductNotFoundException() {
        super("product.not.found");
    }
}
