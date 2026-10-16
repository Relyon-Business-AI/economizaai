package com.relyon.economizaai.exception;

/** SMS provider isn't configured — phone verification can't deliver a code right now. */
public class PhoneVerificationUnavailableException extends DomainException {
    public PhoneVerificationUnavailableException() {
        super("phone.verification.unavailable");
    }
}
