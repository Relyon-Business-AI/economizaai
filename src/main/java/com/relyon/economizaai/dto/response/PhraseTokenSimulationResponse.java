package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * What-if simulation of the dictionary/brand phrase-window size
 * (max-phrase-tokens). For each candidate window N the simulator re-runs the
 * lookup — without persisting anything — and reports two things:
 *
 * <ul>
 *   <li><b>Coverage</b> over the confirmed unmatched backlog: how many distinct
 *       descriptions (and, weighted by frequency, how many items) would get a
 *       dictionary hit at N, plus how many are NEW versus the smallest window.</li>
 *   <li><b>Accuracy</b> over the golden set: category/brand hit-rate at N, so a
 *       wider window that recovers more items but hurts accuracy is visible.</li>
 * </ul>
 *
 * To adopt a window, set the env var ECONOMIZAAI_CATEGORIZATION_MAX_PHRASE_TOKENS
 * — nothing here changes live behavior.
 */
public record PhraseTokenSimulationResponse(
        int baselineTokens,
        int distinctUnmatchedSampled,
        long unmatchedItemsSampled,
        int goldenSetSize,
        List<Row> rows
) {
    public record Row(
            int tokens,
            int distinctCovered,
            double distinctCoveredPct,
            long itemsRecovered,
            int newlyCoveredVsBaseline,
            long newItemsRecoveredVsBaseline,
            int goldenCategoryCorrect,
            double goldenCategoryPct,
            int goldenBrandChecked,
            int goldenBrandCorrect,
            double goldenBrandPct,
            List<String> newlyCoveredExamples
    ) {}
}
