package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Merchant B2B data product: the merchant's OWN stores' activity over time —
 * confirmed receipts, revenue and basket-average, bucketed daily or weekly over
 * the lookback window. This is the merchant's own data (receipts issued at its
 * own CNPJs), so the totals are NOT k-anonymity gated. Any per-product or
 * competitor breakdown lives on the other endpoints and stays K=3.
 */
public record MerchantAnalyticsResponse(
        int lookbackDays,
        /** DAILY or WEEKLY — chosen from the window size. */
        String granularity,
        long totalReceipts,
        BigDecimal totalRevenue,
        BigDecimal basketAverage,
        List<ActivityPoint> series) {

    public record ActivityPoint(
            /** First day of the bucket (the day itself when DAILY, the week's Monday when WEEKLY). */
            LocalDate periodStart,
            long receipts,
            BigDecimal revenue,
            BigDecimal basketAverage) {}
}
