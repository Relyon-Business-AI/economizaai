package com.relyon.economizaai.exception;

/**
 * A marketplace API call failed (network, auth, unexpected payload). Thrown only by
 * flows that want honest errors (garimpo term search); the receipt-path lookups
 * ({@code searchByEan}) stay inert-by-design and never throw. Not a DomainException —
 * callers translate it into a localized one with flow context.
 */
public class EcommerceProviderException extends RuntimeException {

    public EcommerceProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
