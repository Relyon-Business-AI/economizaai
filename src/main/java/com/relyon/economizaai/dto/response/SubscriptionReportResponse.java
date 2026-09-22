package com.relyon.economizaai.dto.response;

import java.util.Map;

/**
 * Subscription mix for the monetization view: how many users on each tier, and
 * within PRO how many are genuinely paying (backed by a payment provider) vs
 * granted by promo/admin. {@code note} flags when paying is still zero so the FE
 * doesn't render a misleading "all PRO" headline while the signup promo is on.
 */
public record SubscriptionReportResponse(
        long totalUsers,
        Map<String, Long> byTier,
        long payingActive,
        long promoGranted,
        String note) {
}
