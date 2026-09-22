package com.relyon.economizaai.exception;

/**
 * Raised when a client posts pre-fetched SEFAZ content for a UF whose portal
 * our server can reach directly. Client-authored content bypasses the
 * server-side fetch — the integrity check that the nota actually exists — so
 * it is only accepted for UFs that block our datacenter IP (e.g. Pernambuco),
 * where fetching on-device is the only option.
 */
public class PrefetchedUnsupportedException extends DomainException {

    public PrefetchedUnsupportedException(String state) {
        super("receipt.prefetched.unsupported", state);
    }
}
