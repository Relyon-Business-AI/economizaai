package com.relyon.economizaai.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Read-only audit of EXISTING products that look mis-merged: a single canonical
 * product whose linked receipt items carry MORE THAN ONE distinct real EAN, or
 * inconsistent distinguishing-token signatures (e.g. "AO LEITE SGLUT" and
 * "50% ZERO" under one Divine product). This is a REPORT for the owner to review
 * — nothing is auto-split or reassigned.
 *
 * @param conflicts the flagged products, worst first (most distinct EANs)
 */
public record VariantConflictResponse(List<VariantConflict> conflicts) {

    /**
     * @param productId        the canonical product
     * @param productName      its normalized name
     * @param productEan       the product's own EAN (may be null)
     * @param distinctRealEans distinct REAL (non-placeholder) EANs seen across linked items
     * @param distinctSignatures distinct distinguishing-token signatures across linked items
     * @param samples          up to a few conflicting item descriptions + EANs for the reviewer
     */
    public record VariantConflict(
            UUID productId,
            String productName,
            String productEan,
            List<String> distinctRealEans,
            List<String> distinctSignatures,
            List<ConflictingItem> samples
    ) {}

    public record ConflictingItem(String description, String ean, String signature) {}
}
