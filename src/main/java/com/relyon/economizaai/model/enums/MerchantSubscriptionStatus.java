package com.relyon.economizaai.model.enums;

/** Merchant marketing-account subscription state (per chain, not per user). */
public enum MerchantSubscriptionStatus {
    /** Launch promo: free until {@code freeUntil} (config economizaai.merchant.free-until). */
    PROMO,
    /** Paid and current (payment integration still INERT — set manually/admin for now). */
    ACTIVE,
    EXPIRED
}
