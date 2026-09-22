package com.relyon.economizaai.exception;

/** Raised when a webhook request's shared-secret header is missing or wrong. Mapped to 401. */
public class InvalidWebhookSecretException extends DomainException {

    public InvalidWebhookSecretException() {
        super("webhook.secret.invalid");
    }
}
