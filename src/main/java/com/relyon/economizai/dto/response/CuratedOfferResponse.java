package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.EcommerceOffer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Full curated-offer row for the admin curation list. */
public record CuratedOfferResponse(
        UUID id,
        String ean,
        UUID productId,
        String provider,
        String title,
        String externalUrl,
        String affiliateUrl,
        String imageUrl,
        BigDecimal price,
        BigDecimal freight,
        String currency,
        boolean inStock,
        boolean active,
        boolean curated,
        String curatedBy,
        LocalDateTime updatedAt) {

    public static CuratedOfferResponse from(EcommerceOffer offer) {
        return new CuratedOfferResponse(
                offer.getId(),
                offer.getEan(),
                offer.getProduct() == null ? null : offer.getProduct().getId(),
                offer.getProvider(),
                offer.getTitle(),
                offer.getExternalUrl(),
                offer.getAffiliateUrl(),
                offer.getImageUrl(),
                offer.getPrice(),
                offer.getFreight(),
                offer.getCurrency(),
                offer.isInStock(),
                offer.isActive(),
                offer.isCurated(),
                offer.getCuratedBy(),
                offer.getUpdatedAt());
    }
}
