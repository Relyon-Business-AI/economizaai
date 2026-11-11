package com.relyon.economizaai.exception;

/** Wrong, expired, exhausted or missing e-mail-change code (400). */
public class InvalidEmailChangeCodeException extends DomainException {

    public InvalidEmailChangeCodeException() {
        super("user.email-change.code-invalid");
    }
}
