package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** A household's manual category correction for a receipt item's product. */
public record UpdateItemCategoryRequest(
        @Schema(description = "Corrected category for this item's product (household-scoped).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull ProductCategory category
) {}
