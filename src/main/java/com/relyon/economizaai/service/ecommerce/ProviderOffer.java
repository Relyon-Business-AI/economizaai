package com.relyon.economizaai.service.ecommerce;

import java.math.BigDecimal;

/**
 * A single offer fetched live from an e-commerce provider (transient — not persisted).
 * {@code freight} is null when the provider couldn't estimate it; {@code total()} then
 * falls back to the item price alone.
 */
public record ProviderOffer(
        String providerKey,
        String title,
        BigDecimal price,
        BigDecimal freight,
        String currency,
        String externalUrl,
        String affiliateUrl,
        String imageUrl,
        boolean inStock) {

    public BigDecimal total() {
        return freight == null ? price : price.add(freight);
    }
}
