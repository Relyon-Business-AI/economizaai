package com.relyon.economizaai.exception;

import lombok.Getter;

/**
 * The client accepted an outdated legal document version. The document id and
 * versions are kept for logs only and never shown in the user-facing message.
 */
@Getter
public class InvalidLegalVersionException extends DomainException {

    private final String document;
    private final String submittedVersion;
    private final String currentVersion;

    public InvalidLegalVersionException(String document, String submitted, String current) {
        super("legal.version.invalid");
        this.document = document;
        this.submittedVersion = submitted;
        this.currentVersion = current;
    }
}
