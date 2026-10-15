package com.relyon.economizaai.exception;

/** A custom category with this name already exists (in the household or the built-in taxonomy). */
public class DuplicateCustomCategoryException extends DomainException {
    public DuplicateCustomCategoryException(String name) {
        super("customcategory.name.duplicate", name);
    }
}
