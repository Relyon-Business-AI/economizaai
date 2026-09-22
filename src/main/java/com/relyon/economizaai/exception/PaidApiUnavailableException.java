package com.relyon.economizaai.exception;

/**
 * The circuit breaker for a paid external service is open — it has failed
 * repeatedly in a short window, so we stop calling it (and stop spending) until
 * the cooldown elapses.
 */
public class PaidApiUnavailableException extends DomainException {

    public PaidApiUnavailableException(String service) {
        super("receipt.paid_api.circuit_open", service);
    }
}
