package com.relyon.economizaai.service.sefaz;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether "tentar de novo" can possibly help a failed nota. Some failures are a final
 * verdict about the nota itself — retrying re-runs (and may pay for) the same fetch only to fail
 * the same way. For those, the user gets a localized message saying what to do instead.
 *
 * <p>Every other failure (portal unstable, timeout, caps, layout we couldn't read…) stays
 * retryable. Keyed by the machine {@code parseErrorReason} ("key:args") stored on the receipt.
 */
public final class ReceiptRetryPolicy {

    /** parseErrorReason (exact, or as a "key:" prefix) → message key explaining the refusal. */
    private static final Map<String, String> REFUSAL_MESSAGE_BY_REASON = deterministicReasons();

    private ReceiptRetryPolicy() {
    }

    private static Map<String, String> deterministicReasons() {
        var reasons = new LinkedHashMap<String, String>();
        // The 44 digits were read wrong (bad check digit / typo) — only a new read fixes it.
        reasons.put("receipt.parse.failed:chave-corrupted", "receipt.retry.refused.chave");
        reasons.put("receipt.chave.typo", "receipt.retry.refused.chave");
        // The provider answered: the portal has no such nota (cancelled / never transmitted).
        reasons.put("receipt.parse.failed:infosimples.not-found", "receipt.retry.refused.not_found");
        // The store never transmitted the contingency nota inside the legal window.
        reasons.put("receipt.contingency.unavailable", "receipt.retry.refused.not_transmitted");
        // Not a grocery/pharmacy merchant.
        reasons.put("receipt.merchant.unsupported", "receipt.retry.refused.merchant");
        // The payload is not an NFC-e QR code at all.
        reasons.put("receipt.qr.invalid", "receipt.retry.refused.qr");
        // No way to read this state / this kind of typed chave yet.
        reasons.put("receipt.state.unsupported", "receipt.retry.refused.state");
        reasons.put("receipt.manual-chave.unsupported", "receipt.retry.refused.manual_chave");
        reasons.put("receipt.manual-chave.nfe-unsupported", "receipt.retry.refused.manual_chave");
        return Map.copyOf(reasons);
    }

    /**
     * The message key that explains why a retry is refused for this failure reason, or empty
     * when a retry may help (including when there is no recorded reason).
     */
    public static Optional<String> refusalMessageKey(String parseErrorReason) {
        if (parseErrorReason == null || parseErrorReason.isBlank()) return Optional.empty();
        return REFUSAL_MESSAGE_BY_REASON.entrySet().stream()
                .filter(entry -> matches(parseErrorReason, entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** True when retrying this failure cannot change the outcome. */
    public static boolean isDeterministicFailure(String parseErrorReason) {
        return refusalMessageKey(parseErrorReason).isPresent();
    }

    private static boolean matches(String parseErrorReason, String deterministicReason) {
        return parseErrorReason.equals(deterministicReason)
                || parseErrorReason.startsWith(deterministicReason + ":");
    }
}
