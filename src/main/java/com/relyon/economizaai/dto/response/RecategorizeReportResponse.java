package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.util.List;
import java.util.UUID;

/**
 * Dry-run of re-categorizing the whole product catalog: re-runs the extraction
 * cascade over each product's stored description and reports where the result
 * differs from what's currently stored. Read-only — apply via POST.
 */
public record RecategorizeReportResponse(
        long totalProducts,
        int mismatchCount,
        int applicableFromDictionary, // trusted (DICTIONARY/LEARNED) suggestions POST will apply
        int skippedUserOverrides,     // mismatches kept because the category was set manually (source=USER)
        List<Row> mismatches
) {
    public record Row(
            UUID productId,
            String normalizedName,
            String ean,
            ProductCategory currentCategory,
            CategorizationSource currentSource,
            ProductCategory suggestedCategory,
            CategorizationSource suggestedSource,
            boolean userOverride
    ) {}
}
