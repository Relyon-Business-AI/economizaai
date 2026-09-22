package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.service.ecommerce.ProviderProduct;

import java.math.BigDecimal;

/**
 * A marketplace product as the garimpo surfaces it: normalized price/discount plus
 * the (best-effort) monetized link. {@code url} is the affiliate link when one could
 * be built, otherwise the plain permalink.
 */
public record GarimpoProductResponse(
        String externalId,
        String marketplace,
        String title,
        BigDecimal price,
        BigDecimal originalPrice,
        Integer discountPercent,
        String currency,
        String url,
        String imageUrl,
        String sellerName,
        boolean freeShipping) {

    public static GarimpoProductResponse from(ProviderProduct product) {
        return new GarimpoProductResponse(
                product.externalId(),
                product.providerKey(),
                product.title(),
                product.price(),
                product.originalPrice(),
                product.discountPercent(),
                product.currency(),
                product.affiliateUrl() != null ? product.affiliateUrl() : product.externalUrl(),
                product.imageUrl(),
                product.sellerName(),
                product.freeShipping());
    }
}
