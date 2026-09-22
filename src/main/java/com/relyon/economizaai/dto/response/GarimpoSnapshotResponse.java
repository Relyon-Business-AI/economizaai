package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.GarimpoPriceSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One point of a product's garimpo price history (written only when the price changed). */
public record GarimpoSnapshotResponse(
        String title,
        BigDecimal price,
        BigDecimal originalPrice,
        Integer discountPercent,
        String currency,
        String sellerName,
        boolean freeShipping,
        String url,
        LocalDateTime capturedAt) {

    public static GarimpoSnapshotResponse from(GarimpoPriceSnapshot snapshot) {
        return new GarimpoSnapshotResponse(
                snapshot.getTitle(),
                snapshot.getPrice(),
                snapshot.getOriginalPrice(),
                snapshot.getDiscountPercent(),
                snapshot.getCurrency(),
                snapshot.getSellerName(),
                snapshot.isFreeShipping(),
                snapshot.getAffiliateUrl() != null ? snapshot.getAffiliateUrl() : snapshot.getExternalUrl(),
                snapshot.getCreatedAt());
    }
}
