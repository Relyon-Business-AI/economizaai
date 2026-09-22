package com.relyon.economizaai.dto.response;

/**
 * Outcome of re-running brand extraction over the catalog to fill products that
 * are missing a brand (brand is otherwise set only once, at product creation).
 * Fill-only: never overwrites an existing brand.
 */
public record BrandBackfillResponse(
        long totalProducts,
        int filled,
        int stillMissing
) {}
