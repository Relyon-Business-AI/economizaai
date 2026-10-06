package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MerchantPromo;
import com.relyon.economizaai.model.enums.MerchantPromoSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A merchant promo as seen in the merchant panel. {@code verifiedByReceipts}
 * is the product's superpower: real scanned NFC-e observations at the chain,
 * within the promo window, at (or below) the announced price.
 */
public record MerchantPromoResponse(
        UUID id,
        String cnpjRoot,
        String ean,
        UUID productId,
        String productName,
        String description,
        BigDecimal promoPrice,
        BigDecimal regularPrice,
        LocalDate startsAt,
        LocalDate endsAt,
        MerchantPromoSource source,
        boolean active,
        boolean verifiedByReceipts) {

    public static MerchantPromoResponse from(MerchantPromo promo, boolean verifiedByReceipts) {
        return new MerchantPromoResponse(
                promo.getId(),
                promo.getCnpjRoot(),
                promo.getEan(),
                promo.getProduct() == null ? null : promo.getProduct().getId(),
                promo.getProduct() == null ? null : promo.getProduct().getNormalizedName(),
                promo.getDescription(),
                promo.getPromoPrice(),
                promo.getRegularPrice(),
                promo.getStartsAt(),
                promo.getEndsAt(),
                promo.getSource(),
                promo.isActive(),
                verifiedByReceipts);
    }
}
