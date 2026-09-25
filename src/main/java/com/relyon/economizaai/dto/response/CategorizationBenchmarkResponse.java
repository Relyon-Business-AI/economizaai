package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * Extraction quality over a curated golden set (description → true
 * category/brand/quantity). Run before/after each enhancement to see whether
 * each field is improving.
 *
 * <p>{@code accuracyPct} is the headline (category). Brand/quantity are checked
 * only on the golden rows that declare a truth for them ({@code *Checked}).
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
        List<Failure> failures
) {
    public record Failure(String description, String field, String expected, String got, String source) {}
}
