package com.relyon.economizaai.service.ecommerce;

import java.math.BigDecimal;

/**
 * A product found by a term search on a marketplace, normalized across providers
 * (transient — the garimpo flow decides what to persist as a price snapshot).
 * {@code discountPercent} is derived from {@code originalPrice} when the marketplace
 * reports one; null when the product isn't discounted.
 */
public record ProviderProduct(
        String providerKey,
        String externalId,
        String title,
        BigDecimal price,
        BigDecimal originalPrice,
        Integer discountPercent,
        String currency,
        String externalUrl,
        String affiliateUrl,
        String imageUrl,
        String sellerName,
        boolean freeShipping) {
}
