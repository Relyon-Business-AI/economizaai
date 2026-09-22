package com.relyon.economizaai.exception;

public class CustomCategoryNotFoundException extends DomainException {
    public CustomCategoryNotFoundException() {
        super("customcategory.not.found");
    }
}
