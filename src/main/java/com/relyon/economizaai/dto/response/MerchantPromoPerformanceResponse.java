package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MerchantPromo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Merchant B2B data product: receipt-backed performance of one announced promo.
 * The redemption proxy is the count of DISTINCT households whose scanned receipts
 * at the chain, inside the promo window, show the product at (or under) the
 * announced price — the same signal that drives the public "verified" badge. It
 * is k-anonymity gated: {@code confirmingHouseholds} is disclosed only when it
 * reaches K=3 (otherwise null, and {@code verified} is false), so a single
 * shopper's purchase is never exposed.
 *
 * <p>No exposure/impression signal exists yet — the sponsored feed
 * ({@code SponsoredPromoService}) serves a list and tracks no views, so
 * {@code exposureTracked} is always false and no view count is reported.
 */
public record MerchantPromoPerformanceResponse(
        UUID promoId,
        String cnpjRoot,
        String ean,
        UUID productId,
        String productName,
        BigDecimal promoPrice,
        LocalDate startsAt,
        LocalDate endsAt,
        boolean live,
        boolean verified,
        /** Distinct households confirming the price — null when below K=3 (fail-closed). */
        Integer confirmingHouseholds,
        /** Always false: no impression tracking exists on the sponsored feed yet. */
        boolean exposureTracked) {

    public static MerchantPromoPerformanceResponse of(MerchantPromo promo, boolean live, boolean verified,
                                                      Integer confirmingHouseholds) {
        return new MerchantPromoPerformanceResponse(
                promo.getId(),
                promo.getCnpjRoot(),
                promo.getEan(),
                promo.getProduct() == null ? null : promo.getProduct().getId(),
                promo.getProduct() == null ? null : promo.getProduct().getNormalizedName(),
                promo.getPromoPrice(),
                promo.getStartsAt(),
                promo.getEndsAt(),
                live,
                verified,
                confirmingHouseholds,
                false);
    }
}
