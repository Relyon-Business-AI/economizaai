package com.relyon.economizaai.exception;

/** Unknown {@code format} on the purchase-history export — only csv/xlsx exist. */
public class InvalidExportFormatException extends DomainException {

    public InvalidExportFormatException(String requested) {
        super("export.format.invalid", requested);
    }
}
