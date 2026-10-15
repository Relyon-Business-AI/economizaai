package com.relyon.economizaai.exception;

/** The requested nesting would break the two-level model (depth &gt; 2, a cycle, or self-parent). */
public class InvalidCategoryNestingException extends DomainException {
    public InvalidCategoryNestingException() {
        super("customcategory.nesting.invalid");
    }
}
