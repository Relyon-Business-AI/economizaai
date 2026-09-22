package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.MerchantSupportOverride;

/**
 * Admin verdict on a merchant: SUPPORTED promotes it into the collaborative
 * index (backfilling observations from already-confirmed receipts), BLOCKED
 * rejects its future scans, null clears the override back to segment-driven.
 */
public record MerchantSupportOverrideRequest(MerchantSupportOverride override) {
}
