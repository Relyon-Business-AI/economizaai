package com.relyon.economizaai.model.enums;

/**
 * Admin verdict on a grey-zone merchant, overriding the CNAE-derived segment
 * in the support gate. SUPPORTED promotes the merchant into the collaborative
 * index (an informal padaria with a generic CNAE); BLOCKED rejects its future
 * scans outright (a restaurant hiding behind a retail CNAE).
 */
public enum MerchantSupportOverride {
    SUPPORTED,
    BLOCKED
}
