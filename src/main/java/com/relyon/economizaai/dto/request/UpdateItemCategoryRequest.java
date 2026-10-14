package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * A household's manual category correction for a receipt item's product. Set
 * EXACTLY ONE target: a global enum {@code category}, a household-scoped
 * {@code customCategoryId}, or a new-taxonomy {@code categoryNodeSlug} (fine leaf/group).
 */
public record UpdateItemCategoryRequest(
        @Schema(description = "Global enum category. Set this OR customCategoryId OR categoryNodeSlug.")
        ProductCategory category,
        @Schema(description = "Custom (household) category id.")
        UUID customCategoryId,
        @Schema(description = "New-taxonomy node slug (e.g. 'queijos', 'carnes'). Set this to correct into a fine category.")
        String categoryNodeSlug
) {}
