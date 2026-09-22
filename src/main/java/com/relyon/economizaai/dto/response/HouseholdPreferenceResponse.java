package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Auto-derived per-generic preferences for a household. Pack size carries
 * a typical-range so downstream consumers (suggested list, best-market
 * filter) can soft-rank rather than hard-exclude items outside the range.
 *
 * <p>brandStrength is one of: PREFERRED (top brand 60–85% share),
 * MUST_HAVE (>85% share). NEUTRAL is implicit — products without enough
 * concentration are simply omitted.
 */
public record HouseholdPreferenceResponse(
        String genericName,
        BigDecimal preferredPackSize,
        String preferredPackUnit,
        BigDecimal minObservedPackSize,
        BigDecimal maxObservedPackSize,
        String topBrand,
        BrandStrength brandStrength,
        BigDecimal topBrandShare,
        List<BrandShare> brandDistribution,
        int sampleSize,
        Confidence confidence
) {
    public enum BrandStrength { PREFERRED, MUST_HAVE }
    public enum Confidence { LOW, MEDIUM, HIGH }
    public record BrandShare(String brand, BigDecimal share, int count) {}
}
