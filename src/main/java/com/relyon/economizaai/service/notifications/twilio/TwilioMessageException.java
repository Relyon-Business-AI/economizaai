package com.relyon.economizaai.service.notifications.twilio;

/**
 * Raised when Twilio rejects a send (HTTP error, bad credentials, etc.). The
 * dispatcher catches this and records a graceful delivery failure — it never
 * bubbles to the notification caller.
 */
public class TwilioMessageException extends RuntimeException {

    public TwilioMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
