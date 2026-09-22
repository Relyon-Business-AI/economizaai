package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.EanCatalogEntry;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a barcode scan lookup. Exactly one of {@code product} /
 * {@code catalogPreview} is non-null:
 * <ul>
 *   <li>{@code product} — we already track this product; follow up with
 *       {@code GET /price-index/products/{id}/best-markets} for nearby prices.</li>
 *   <li>{@code catalogPreview} — the barcode is known to the EAN catalog but no
 *       one scanned it in a receipt yet, so there is no price data. Show the
 *       preview and invite the user to scan their receipt.</li>
 * </ul>
 */
public record EanLookupResponse(
        @Schema(description = "true when the product exists in our base (price queries possible)")
        boolean known,
        ProductResponse product,
        CatalogPreview catalogPreview
) {
    public record CatalogPreview(String ean, String name, String brand, String category) {}

    public static EanLookupResponse ofProduct(ProductResponse product) {
        return new EanLookupResponse(true, product, null);
    }

    public static EanLookupResponse ofCatalog(EanCatalogEntry entry) {
        return new EanLookupResponse(false, null, new CatalogPreview(
                entry.getEan(), entry.getGenericName(), entry.getBrand(),
                entry.getCategory() == null ? null : entry.getCategory().name()));
    }
}
