package com.relyon.economizaai.model.enums;

/**
 * A metered external service that costs money per call. Used as the ledger key
 * in {@code paid_api_call} and the dimension for per-user daily caps and the
 * Infosimples circuit breaker.
 */
public enum PaidApiService {

    /** Infosimples SEFAZ-by-chave query (~R$0.24). CE every note + fallback everywhere. */
    INFOSIMPLES(24),

    /** A single captcha solve via CapSolver/2Captcha (~R$0.03). Every scrape solves at least one. */
    CAPTCHA_SOLVE(3),

    /** One Twilio SMS/WhatsApp message to Brazil (~R$0.30). OTPs and SMS/WhatsApp notifications. */
    TWILIO_MESSAGE(30),

    /** One batched LLM enrichment call (category/brand/pack for ~25 products, ~R$0.02). */
    LLM_ENRICH(2),

    /** One LLM vision extraction of a photographed receipt (~R$0.10). */
    LLM_VISION(10);

    private final int defaultCostCents;

    PaidApiService(int defaultCostCents) {
        this.defaultCostCents = defaultCostCents;
    }

    public int defaultCostCents() {
        return defaultCostCents;
    }
}
