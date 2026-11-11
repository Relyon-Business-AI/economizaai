package com.relyon.economizaai.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Plan (dry-run) or result (applied) of the one-time variant-split cleanup, which
 * repairs products the OLD matcher wrongly merged — a single {@code products} row
 * holding items of DIFFERENT variants/brands/flavours (e.g. "CR LTE NESTLE 200G"
 * carrying both a Nestlé and a Dália EAN).
 *
 * <p>Each candidate product's linked items are grouped by their VARIANT SIGNATURE
 * (brand + the distinguishing tokens from {@link
 * com.relyon.economizaai.service.canonicalization.VariantDiscriminator}). A product
 * whose items span more than one signature is split: one signature stays on the
 * original row, every other becomes a NEW product with the rows of that signature
 * reassigned. Products with a single signature are skipped (idempotent).
 *
 * @param dryRun          true when nothing was mutated (the plan only)
 * @param candidates      products examined (had > 1 distinct item EAN pre-filter)
 * @param productsSplit   products that actually had multiple signatures and were split
 * @param newProductsCreated total new product rows created across all splits
 * @param splits          per-split detail
 */
public record ProductVariantSplitResponse(
        boolean dryRun,
        int candidates,
        int productsSplit,
        int newProductsCreated,
        List<ProductSplitPlan> splits
) {

    /**
     * One mis-merged product and how its items regroup by signature.
     *
     * @param productId         the original (mis-merged) product
     * @param productName       its normalized name
     * @param genericNameNorm   the type key — PRESERVED identically on every resulting product
     * @param anchorSignature   the signature kept on the original row
     * @param resultingProducts every signature group, anchor first
     */
    public record ProductSplitPlan(
            UUID productId,
            String productName,
            String genericNameNorm,
            String anchorSignature,
            List<ResultingProduct> resultingProducts
    ) {}

    /**
     * One signature group of a split.
     *
     * @param signature            the variant signature (brand + distinguishing tokens)
     * @param anchor               true when this group stays on the original product row
     * @param newProductId         id of the created product (null for the anchor / in dry-run)
     * @param brand                representative brand for this group (may be null)
     * @param representativeEan    representative real EAN for this group (may be null)
     * @param representativeName   representative normalized name for this group
     * @param distinctEans         distinct real EANs in this group
     * @param itemCount            receipt_items that go to this group
     * @param observationCount     price_observations that go to this group
     * @param aliasCount           product_aliases that go to this group
     * @param observationsUntraced observations that could not be traced to an item (kept on the original)
     */
    public record ResultingProduct(
            String signature,
            boolean anchor,
            UUID newProductId,
            String brand,
            String representativeEan,
            String representativeName,
            List<String> distinctEans,
            long itemCount,
            long observationCount,
            long aliasCount,
            long observationsUntraced
    ) {}
}
