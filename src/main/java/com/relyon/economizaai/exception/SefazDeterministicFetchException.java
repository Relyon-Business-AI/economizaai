package com.relyon.economizaai.exception;

/**
 * Deterministic portal 4xx (400/404/422 — bad or unknown chave): retrying or the
 * paid Infosimples fallback cannot rescue it, so it must propagate WITHOUT
 * spending money. IP-block/throttle statuses (403/429) stay a plain
 * {@link SefazFetchException} — those ARE plausibly rescuable by the fallback.
 * Same message key as the parent, so the user-facing failure is unchanged.
 */
public class SefazDeterministicFetchException extends SefazFetchException {

    public SefazDeterministicFetchException(String state) {
        super(state);
    }
}
