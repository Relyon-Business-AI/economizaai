package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Slim view of a product that's missing its brand, plus a few sample raw
 * descriptions from its aliases — gives the admin enough context to assign
 * a brand without having to round-trip to the alias detail.
 */
public record MissingBrandProductResponse(
        UUID id,
        String ean,
        String normalizedName,
        String genericName,
        ProductCategory category,
        BigDecimal packSize,
        String packUnit,
        List<String> sampleDescriptions
) {
    public static MissingBrandProductResponse from(Product product, List<String> sampleDescriptions) {
        return new MissingBrandProductResponse(
                product.getId(),
                product.getEan(),
                product.getNormalizedName(),
                product.getGenericName(),
                product.getCategory(),
                product.getPackSize(),
                product.getPackUnit(),
                sampleDescriptions
        );
    }
}
