package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String ean,
        String normalizedName,
        String genericName,
        String friendlyDescription,
        String brand,
        ProductCategory category,
        String unit,
        BigDecimal packSize,
        String packUnit,
        CategorizationSource categorizationSource,
        boolean hasPriceHistory
) {
    /**
     * Without price-history or household context: defaults {@code hasPriceHistory}
     * to false and {@code friendlyDescription} to null. Used by callers (single get,
     * post-write echoes) that don't compute the collaborative k-anonymity count or
     * don't have a household in scope.
     */
    public static ProductResponse from(Product product) {
        return from(product, false, null);
    }

    /**
     * {@code hasPriceHistory} is true when enough distinct households contributed
     * price observations for this product to clear k-anonymity — i.e. the
     * collaborative price history is publicly displayable. Computed in batch by
     * the search path; see {@code ProductService.search}.
     */
    public static ProductResponse from(Product product, boolean hasPriceHistory) {
        return from(product, hasPriceHistory, null);
    }

    /**
     * @param friendlyDescription the household's own rename of this product
     *                            (household_product_aliases), null when never
     *                            renamed or when no household is in scope. Takes
     *                            precedence over {@code genericName}/{@code
     *                            normalizedName} for display — see FE ProductResponse usage.
     */
    public static ProductResponse from(Product product, boolean hasPriceHistory, String friendlyDescription) {
        return new ProductResponse(
                product.getId(),
                product.getEan(),
                product.getNormalizedName(),
                product.getGenericName(),
                friendlyDescription,
                product.getBrand(),
                product.getCategory(),
                product.getUnit(),
                product.getPackSize(),
                product.getPackUnit(),
                product.getCategorizationSource(),
                hasPriceHistory
        );
    }
}
