package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Move the selected products into a target category (household-scoped). Provide
 * exactly one of {@code targetCategory} (global enum) or {@code targetCustomCategoryId}.
 */
public record MigrateCategoryRequest(
        // @NotNull no elemento: Jackson converte "" em null dentro da lista e um
        // id nulo estourava 500 no findById — vira 400 de validação.
        @Schema(description = "Products to migrate.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<@NotNull UUID> productIds,
        @Schema(description = "Target global category (mutually exclusive with targetCustomCategoryId).")
        ProductCategory targetCategory,
        @Schema(description = "Target custom category id (mutually exclusive with targetCategory).")
        UUID targetCustomCategoryId
) {}
