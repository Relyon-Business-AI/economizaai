package com.relyon.economizaai.exception;

/** A purchase date typed on a photo nota that can't be real (in the future or before 2000). */
public class InvalidIssuedAtException extends DomainException {

    public InvalidIssuedAtException() {
        super("receipt.issued-at.invalid");
    }
}
