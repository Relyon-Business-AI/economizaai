package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Move the selected products into a target category (household-scoped). Provide
 * exactly one of {@code targetCategory} (global enum) or {@code targetCustomCategoryId}.
 */
public record MigrateCategoryRequest(
        @Schema(description = "Products to migrate.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<UUID> productIds,
        @Schema(description = "Target global category (mutually exclusive with targetCustomCategoryId).")
        ProductCategory targetCategory,
        @Schema(description = "Target custom category id (mutually exclusive with targetCategory).")
        UUID targetCustomCategoryId
) {}
