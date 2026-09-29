package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * "Live" categorization accuracy measured against REAL human-validated products
 * (categorizationSource USER or CONSENSUS) instead of the fixed golden set. For
 * each such product we re-run the deterministic engine on its description and
 * compare to the human/community truth — so the number reflects the real
 * incoming distribution, and {@code mismatches} are genuine error cases to fix.
 */
public record LiveAccuracyResponse(
        int total,
        int correct,
        double accuracyPct,
        List<Mismatch> mismatches) {

    public record Mismatch(String description, String expectedCategory, String actualCategory, String engineSource) {}
}
