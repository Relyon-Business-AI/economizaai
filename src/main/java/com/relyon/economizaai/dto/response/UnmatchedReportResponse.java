package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * Item→product matching diagnostics: how many confirmed items linked to a
 * canonical product vs stayed orphaned (UNMATCHED, hence out of the price
 * index), plus the most frequent orphan descriptions to guide curation.
 *
 * @param matchedItems     confirmed, non-excluded items linked to a product
 * @param unmatchedItems   confirmed, non-excluded items with no product
 * @param unmatchedPct     unmatched / (matched + unmatched) — the core KPI
 * @param topUnmatched     most frequent orphan descriptions, worst first
 */
public record UnmatchedReportResponse(
        long matchedItems,
        long unmatchedItems,
        double unmatchedPct,
        List<UnmatchedDescription> topUnmatched
) {
    public record UnmatchedDescription(String description, long occurrences) {}
}
