package com.relyon.economizaai.exception;

/** The weekly-report toggle can only be changed after the user has linked (opted in) their WhatsApp. */
public class WhatsAppOptInRequiredException extends DomainException {

    public WhatsAppOptInRequiredException() {
        super("whatsapp.optin.required");
    }
}
