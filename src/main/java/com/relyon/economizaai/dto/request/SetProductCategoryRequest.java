package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record SetProductCategoryRequest(
        @Schema(description = "Global category for the canonical product. Sets Product.category and LOCKS it " +
                "as a manual (USER) decision, so the recategorize/LLM cascade never overrides it.",
                example = "PET_SUPPLIES")
        @NotNull ProductCategory category
) {}
