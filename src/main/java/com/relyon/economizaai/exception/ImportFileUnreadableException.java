package com.relyon.economizaai.exception;

/** The uploaded import file (CSV/Excel/PDF) could not be read as any supported format. */
public class ImportFileUnreadableException extends DomainException {

    public ImportFileUnreadableException() {
        super("receipt.import.file.unreadable");
    }
}
