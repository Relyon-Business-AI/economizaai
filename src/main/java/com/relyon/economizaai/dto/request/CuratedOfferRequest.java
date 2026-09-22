package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Admin payload to curate an online offer manually (precision-first MVP): an EAN →
 * a specific e-commerce product with its price and (affiliate) link.
 */
public record CuratedOfferRequest(
        @NotBlank @Size(max = 14) String ean,
        UUID productId,
        @NotBlank @Size(max = 40) String provider,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 1024) String externalUrl,
        @Size(max = 1024) String affiliateUrl,
        @Size(max = 1024) String imageUrl,
        @NotNull @DecimalMin("0.0") BigDecimal price,
        @DecimalMin("0.0") BigDecimal freight,
        @Size(max = 3) String currency,
        Boolean inStock,
        Boolean active) {
}
