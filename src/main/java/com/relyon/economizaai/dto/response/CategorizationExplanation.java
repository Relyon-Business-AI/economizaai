package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;

/**
 * Dry-run categorization result for a raw product description — what the
 * cascade WOULD assign, with a per-layer breakdown so you can see exactly why.
 * Computed in-memory; nothing is persisted.
 */
public record CategorizationExplanation(
        String input,
        ProductCategory category,
        String genericName,
        String brand,
        BigDecimal packSize,
        String packUnit,
        CategorizationSource source,
        DictionaryHit dictionary
) {
    /** What the dictionary (curated + auto-promoted learned entries) matched, if anything. */
    public record DictionaryHit(String genericName, ProductCategory category, CategorizationSource source) {}
}
