package com.relyon.economizai.dto.response;

import java.math.BigDecimal;

/**
 * The best online offer for a scanned item — powers the "vale a pena online?" card.
 * {@code total} = price + freight. {@code worthIt} is true only when buying online
 * would beat what the user paid by at least the configured margin; {@code savings}
 * is {@code paidPrice − total} (can be negative — the FE shows "não compensa").
 */
public record EcommerceOfferResponse(
        String provider,
        String title,
        BigDecimal price,
        BigDecimal freight,
        BigDecimal total,
        String currency,
        String externalUrl,
        String affiliateUrl,
        String imageUrl,
        boolean inStock,
        boolean curated,
        boolean worthIt,
        BigDecimal paidPrice,
        BigDecimal savings) {
}
