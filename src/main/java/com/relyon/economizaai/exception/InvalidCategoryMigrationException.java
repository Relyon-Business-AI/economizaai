package com.relyon.economizaai.exception;

public class InvalidCategoryMigrationException extends DomainException {
    public InvalidCategoryMigrationException() {
        super("customcategory.migration.invalid");
    }
}
