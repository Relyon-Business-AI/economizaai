package com.relyon.economizaai.model.enums;

/**
 * A metered external service that costs money per call. Used as the ledger key
 * in {@code paid_api_call} and the dimension for per-user daily caps and the
 * Infosimples circuit breaker. What one call costs lives in {@code PaidApiCallCosts}.
 */
public enum PaidApiService {

    /** Infosimples SEFAZ-by-chave query. CE every note + fallback everywhere. */
    INFOSIMPLES,

    /** A single captcha solve via CapSolver/2Captcha. Every scrape solves at least one. */
    CAPTCHA_SOLVE,

    /** One Twilio SMS/WhatsApp message to Brazil. OTPs and SMS/WhatsApp notifications. */
    TWILIO_MESSAGE,

    /** One batched LLM enrichment call (category/brand/pack for ~25 products). */
    LLM_ENRICH,

    /** One LLM vision extraction of a photographed receipt. */
    LLM_VISION,

    /** One LLM extraction of an already-fetched DANFE HTML the parser failed on. */
    LLM_HTML_RESCUE
}
