package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Merchant B2B data product: an anonymized demand signal — the share of scanned
 * demand by brand within the merchant's region (the states its chain operates
 * in) over the lookback window, optionally narrowed to one category. A brand
 * appears ONLY when at least K=3 distinct households contributed observations for
 * it (fail-closed), so no brand's presence can be traced to one shopper. Shares
 * are computed over the brands that passed the gate.
 */
public record MerchantBrandShareResponse(int lookbackDays, String categorySlug,
                                         List<String> states, List<BrandShare> brands) {

    public record BrandShare(
            String brand,
            /** Demand weight: non-outlier in-store observations of the brand in the region/window. */
            long observations,
            /** Distinct contributing households — always >= K (the gate). */
            long households,
            /** observations / total observations among K-passing brands, in %, one decimal. */
            BigDecimal sharePercent) {}
}
