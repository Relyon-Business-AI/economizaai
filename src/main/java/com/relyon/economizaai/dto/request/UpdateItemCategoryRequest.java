package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * A household's manual category correction for a receipt item's product. Set
 * EXACTLY ONE target: a global enum {@code category} OR a household-scoped
 * {@code customCategoryId}.
 */
public record UpdateItemCategoryRequest(
        @Schema(description = "Global enum category. Set this OR customCategoryId, not both.")
        ProductCategory category,
        @Schema(description = "Custom (household) category id. Set this OR category, not both.")
        UUID customCategoryId
) {}
