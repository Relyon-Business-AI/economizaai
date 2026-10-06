package com.relyon.economizaai.model.enums;

/** Lifecycle of a self-serve merchant claim (docs/MERCHANT_ACCOUNTS.md, Fase 1.5). */
public enum MerchantClaimStatus {
    /** Code sent to the company e-mail registered at the Receita — waiting for the user to type it. */
    AWAITING_CODE,
    /** No usable company e-mail — waiting for a manual admin verdict. */
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}
