package com.relyon.economizaai.service.analytics.meta;

/**
 * Raised when the Meta Graph API returns a non-2xx status or an {@code error}
 * payload. Unchecked so the sync job catches it explicitly and logs
 * {@code meta.sync.failed} without dying — the client itself never retries.
 */
public class MetaAdsApiException extends RuntimeException {

    public MetaAdsApiException(String message) {
        super(message);
    }

    public MetaAdsApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
