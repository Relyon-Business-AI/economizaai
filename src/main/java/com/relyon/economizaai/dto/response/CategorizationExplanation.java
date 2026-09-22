package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;

/**
 * Dry-run categorization result for a raw product description — what the
 * cascade WOULD assign, with a per-layer breakdown so you can see exactly
 * why. Computed in-memory; nothing is persisted.
 *
 * <p>The top-level fields are the final decision (what a new Product would get).
 * {@link #dictionary} shows what the curated/learned dictionary alone matched,
 * and {@link #mlCategory} / {@link #mlGenericName} show the ML model's guess +
 * confidence (even when below threshold). {@link #source} tells you which layer
 * won — so a wrong category is immediately traceable to DICTIONARY vs ML.
 */
public record CategorizationExplanation(
        String input,
        ProductCategory category,
        String genericName,
        String brand,
        BigDecimal packSize,
        String packUnit,
        CategorizationSource source,
        DictionaryHit dictionary,
        MlGuess mlCategory,
        MlGuess mlGenericName,
        boolean mlReady,
        boolean mlApplied,
        double mlConfidenceThreshold
) {
    /** What the dictionary (curated CSV + auto-promoted learned entries) matched, if anything. */
    public record DictionaryHit(String genericName, ProductCategory category, CategorizationSource source) {}

    /** The ML model's prediction and how confident it was. {@code meetsThreshold} = would actually be applied. */
    public record MlGuess(String label, Double confidence, boolean meetsThreshold) {}
}
