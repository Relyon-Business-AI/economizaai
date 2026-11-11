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
 * Two cohesion guards are enforced OVER the AI (fail-closed, never overridable):
 * items sharing the same real GTIN are merged into one group even if the AI split them,
 * and items whose NORMALIZED raw description is identical are merged into one group even
 * if the AI split them (different barcodes are NOT reliable evidence of a different
 * consumer product — a re-barcode is common, and the receipt name is the honest signal).
 *
 * <p><b>Safe-apply gate.</b> A proposed split is only materialized on apply when it is
 * HIGH-confidence (the AI said so) AND the separated groups carry MATERIALLY DIFFERENT
 * names (a visible brand/flavour/variant difference). Low-confidence or name-identical
 * separations are dropped — the items stay merged. {@code dryRun=true} reports, per
 * product, whether its split is SAFE-to-apply or SKIPPED (with the reason), so a human
 * previews exactly what a real apply would change.
 *
 * @param aiAvailable       false when no AI provider has an API key — then the strategy
 *                          is a no-op and {@code splits} is empty
 * @param dryRun            true when nothing was mutated (the plan only)
 * @param candidates        products STILL mixed after the deterministic pass (gate output)
 * @param productsSplit     products the AI split AND the safe-apply gate accepted
 * @param newProductsCreated total new product rows created across all splits
 * @param splits            per-split detail — same shape the deterministic split reports,
 *                          each group's label carries the AI-derived brand/variant.
 *                          On a real apply, only SAFE splits appear here; in dry-run,
 *                          SAFE splits appear here and {@code safety} explains each.
 * @param safety            per-product safe-apply verdict (dry-run only): SAFE splits that
 *                          would be applied and SKIPPED ones that would be dropped, each
 *                          with a machine reason. Empty on a real apply.
 */
public record AiProductVariantSplitResponse(
        boolean aiAvailable,
        boolean dryRun,
        int candidates,
        int productsSplit,
        int newProductsCreated,
        List<ProductSplitPlan> splits,
        List<SplitSafety> safety
) {

    /**
     * One product's safe-apply verdict (populated in dry-run). {@code safeToApply=false}
     * means a real apply would DROP this split and keep the items merged.
     *
     * @param productId      the still-mixed product the AI proposed to split
     * @param productName    its normalized name
     * @param proposedGroups how many groups the AI+cohesion guards produced (>1 ⇒ a split)
     * @param confidence     the AI's confidence for this split (HIGH / LOW)
     * @param safeToApply    true when the split would be materialized on a real apply
     * @param reason         machine reason: {@code SAFE}, {@code LOW_CONFIDENCE},
     *                       {@code NAMES_NOT_MATERIALLY_DIFFERENT}, or {@code NO_SPLIT}
     */
    public record SplitSafety(
            java.util.UUID productId,
            String productName,
            int proposedGroups,
            String confidence,
            boolean safeToApply,
            String reason
    ) {}

    /** The no-op response when AI is inert (no API key). */
    public static AiProductVariantSplitResponse unavailable(boolean dryRun) {
        return new AiProductVariantSplitResponse(false, dryRun, 0, 0, 0, List.of(), List.of());
    }
}
