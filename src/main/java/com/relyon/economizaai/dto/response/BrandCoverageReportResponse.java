package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * Dry-run brand-extraction report: runs the brand registry over every product's
 * description WITHOUT persisting, and measures coverage. Answers "how well can
 * we determine brands from the base we have?".
 *
 * @param totalProducts        products scanned
 * @param alreadyHaveBrand     products that already carry a brand
 * @param wouldFillNow         products with no brand that the registry WOULD fill
 * @param stillNoBrand         products that would remain brandless
 * @param coveragePctAfter     % of products with a brand if wouldFillNow were applied
 * @param sampleWouldFill      sample of (description → brand) the run would set
 */
public record BrandCoverageReportResponse(
        long totalProducts,
        long alreadyHaveBrand,
        long wouldFillNow,
        long stillNoBrand,
        double coveragePctAfter,
        List<SampleFill> sampleWouldFill
) {
    public record SampleFill(String description, String brand) {}
}
