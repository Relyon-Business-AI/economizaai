package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Merchant B2B data product: per category (top-level taxonomy group), how the
 * merchant's chain prices against the regional (same-state) market over the
 * lookback window. Each category figure is rolled up ONLY from (product, state)
 * pairs that individually passed the FULL k-anonymity gate (K=3 distinct
 * households AND the minimum-sample floor on BOTH the chain and the region side),
 * so no sub-K data ever reaches the aggregate (fail-closed).
 */
public record MerchantCategoryBenchmarkResponse(int lookbackDays, List<CategoryBenchmark> categories) {

    public record CategoryBenchmark(
            UUID categoryId,
            String categorySlug,
            String categoryName,
            String state,
            /** Median of the per-product chain medians in this (category, state). */
            BigDecimal chainMedianPrice,
            /** Median of the per-product regional medians in this (category, state). */
            BigDecimal regionMedianPrice,
            /** Median of the per-product (chain - region)/region deltas, %, + = chain is pricier. */
            BigDecimal deltaPercent,
            /** Distinct products contributing — every one already passed K=3 on both sides. */
            int productCount) {}
}
