package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * Extraction quality over a curated golden set (description → true
 * category/brand/quantity). Run before/after each enhancement to see whether
 * each field is improving.
 *
 * <p>{@code accuracyPct} is the headline (category). Brand/quantity are checked
 * only on the golden rows that declare a truth for them ({@code *Checked}).
 * {@code mlCategory*} is a SHADOW measurement of the ML model alone (even while
 * it's gated out of the live cascade) so we can tell when it's good enough to
 * re-enable.
 */
public record CategorizationBenchmarkResponse(
        int total,
        int correct,
        double accuracyPct,
        int wrong,
        int uncategorized,
        int brandChecked,
        int brandCorrect,
        double brandAccuracyPct,
        int quantityChecked,
        int quantityCorrect,
        double quantityAccuracyPct,
        int mlCategoryChecked,
        int mlCategoryCorrect,
        double mlCategoryAccuracyPct,
        List<Failure> failures
) {
    public record Failure(String description, String field, String expected, String got, String source) {}
}
