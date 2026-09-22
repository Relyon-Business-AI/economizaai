package com.relyon.economizaai.exception;

public class ProductAliasConflictException extends DomainException {

    public ProductAliasConflictException(String description) {
        super("product.alias.conflict", description);
    }
}
