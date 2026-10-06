package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Merchant mini-panel: for each product observed at the merchant's chain, the
 * chain's median price vs. the regional (same-state, whole-index) median over
 * the collaborative lookback window. Only rows passing k-anonymity AND the
 * minimum-sample threshold on BOTH sides are included — rows below either gate
 * are silently omitted (no sub-K data ever leaves the query layer).
 */
public record MerchantPriceComparisonResponse(int lookbackDays, List<ProductComparison> products) {

    public record ProductComparison(
            UUID productId,
            String productName,
            String state,
            BigDecimal chainMedianPrice,
            int chainSampleCount,
            BigDecimal regionMedianPrice,
            int regionSampleCount,
            /** (chain - region) / region, in %, positive = chain is more expensive. */
            BigDecimal deltaPercent) {}
}
