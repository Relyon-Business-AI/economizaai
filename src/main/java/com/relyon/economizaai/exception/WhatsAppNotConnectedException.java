package com.relyon.economizaai.exception;

/** A WhatsApp option can only be switched on after the user connected (opted in) their WhatsApp. */
public class WhatsAppNotConnectedException extends DomainException {

    public WhatsAppNotConnectedException() {
        super("notification.settings.whatsapp.not-connected");
    }
}
