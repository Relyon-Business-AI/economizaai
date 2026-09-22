package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;

/**
 * Admin report for validating the relevance-feedback filter (SHADOW → ON
 * decision). {@code engagement} describes how users interact with deals in the
 * window; {@code suppression} describes what the filter suppresses (or would
 * suppress, in SHADOW) and the <b>regret</b> it would cause: engagement events
 * (taps / list-adds / conversions) on a product the same user had recently
 * dismissed or muted — interactions the filter would have prevented. Near-zero
 * regret with meaningful signal volume = safe to turn ON; rising dismissal
 * rate after ON = turn it back off.
 */
public record RelevanceReportResponse(
        String mode,
        int windowDays,
        int dismissedDays,
        int mutedDays,
        Engagement engagement,
        Suppression suppression
) {
    public record Engagement(
            long sent,
            long pushOpened,
            long screenOpened,
            long dealViews,
            long dealTaps,
            long addedToList,
            long dismissed,
            long muted,
            long conversions,
            BigDecimal attributedSavings,
            /** dealTaps / dealViews — should rise (or hold) when the filter helps. */
            BigDecimal tapThroughRate,
            /** dismissed / dealViews — should fall when the filter helps. */
            BigDecimal dismissalRate
    ) {}

    public record Suppression(
            long signals,
            long usersWithSignals,
            long productsAffected,
            /** Engagements (tap/add/convert) on suppressed products inside their suppression window. */
            long regretEngagements,
            /** R$ of CONVERTED savings the filter would have blocked — the cost of being wrong. */
            BigDecimal regretSavings
    ) {}
}
