package com.relyon.economizaai.service.subscription;

/**
 * The set of capabilities gated behind the PRO tier. Each value names a
 * distinct paywall the {@link SubscriptionGateService} can be asked about.
 * FREE users are blocked from these; PRO users are allowed everything.
 */
public enum Feature {
    /** Pin more than the FREE limit of watched markets. */
    WATCHED_MARKETS_UNLIMITED,
    /** Query analytics/history beyond the FREE rolling window. */
    HISTORY_FULL_RANGE,
    /** Deliver notifications via push/email (FREE is in-app inbox only). */
    PUSH_AND_EMAIL_DELIVERY,
    /** Submit more than the FREE monthly receipt cap. */
    RECEIPT_UPLOAD_UNLIMITED,
    /** Cross-market basket optimization of a shopping list. */
    BASKET_OPTIMIZATION,
    /** CSV export of the household's purchase history (power users / accounting). */
    CSV_EXPORT,
    /** Vision extraction of a photographed receipt (LLM cost per use). */
    PHOTO_EXTRACTION
}
