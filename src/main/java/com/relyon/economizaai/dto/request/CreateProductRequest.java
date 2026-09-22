package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(
        @Schema(description = "EAN-13/14 barcode (digits only). Optional for branded products that lack one.",
                example = "7891991015462")
        @Size(max = 14) String ean,
        @Schema(description = "Display name (cleaned-up, human-readable). Used as the canonical label across the app.",
                example = "Cerveja Stella Artois 330ml LN")
        @NotBlank @Size(max = 255) String normalizedName,
        @Schema(description = "Category-agnostic name shared across brands (e.g. 'Cerveja' covers Stella + Brahma + Skol).",
                example = "Cerveja")
        @Size(max = 100) String genericName,
        @Schema(description = "Brand name as printed on the product.", example = "Stella Artois")
        @Size(max = 100) String brand,
        @Schema(description = "Aisle classification used by spend-by-category insights.")
        ProductCategory category,
        @Schema(description = "Sales unit on the receipt (UN, KG, L, etc).", example = "UN")
        @Size(max = 10) String unit,
        @Schema(description = "Pack quantity per unit (e.g. 5 for a 5kg bag).", example = "330")
        @DecimalMin(value = "0.0") BigDecimal packSize,
        @Schema(description = "Pack-quantity unit (KG, G, L, ML).", example = "ML")
        @Size(max = 10) String packUnit
) {}
