package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.dto.response.ProductVariantSplitResponse.ProductSplitPlan;

import java.util.List;

/**
 * Plan (dry-run) or result (applied) of the AI-assisted variant split — the long-tail
 * cleanup for products the deterministic dictionary ({@code BrandExtractor} +
 * {@code VariantDiscriminator}) CANNOT disambiguate (e.g. "CAFE MELITTA 500G" still
 * mixing Pimpinela / Canecão / Melitta because the extractor returns "café" for all).
 *
 * <p>The candidate gate selects only products STILL mixed after the deterministic pass
 * (their items span more than one real EAN OR more than one deterministic signature),
 * then asks the LLM to cluster each product's distinct items into real products.
 * EAN cohesion is enforced OVER the AI: items sharing the same real GTIN are merged
 * into one group even if the AI split them.
 *
 * @param aiAvailable       false when no AI provider has an API key — then the strategy
 *                          is a no-op and {@code splits} is empty
 * @param dryRun            true when nothing was mutated (the plan only)
 * @param candidates        products STILL mixed after the deterministic pass (gate output)
 * @param productsSplit     products the AI actually clustered into more than one product
 * @param newProductsCreated total new product rows created across all splits
 * @param splits            per-split detail — same shape the deterministic split reports,
 *                          each group's label carries the AI-derived brand/variant
 */
public record AiProductVariantSplitResponse(
        boolean aiAvailable,
        boolean dryRun,
        int candidates,
        int productsSplit,
        int newProductsCreated,
        List<ProductSplitPlan> splits
) {

    /** The no-op response when AI is inert (no API key). */
    public static AiProductVariantSplitResponse unavailable(boolean dryRun) {
        return new AiProductVariantSplitResponse(false, dryRun, 0, 0, 0, List.of());
    }
}
