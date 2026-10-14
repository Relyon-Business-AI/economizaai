package com.relyon.economizaai.exception;

/** Raised when a category correction gives neither or both of an enum/custom target. */
public class InvalidCategoryTargetException extends DomainException {
    public InvalidCategoryTargetException() {
        super("category.target.invalid");
    }
}
