package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create a household custom category (e.g. "Frutas"), optionally with a visual
 *  (icon+color) and nested under a taxonomy group. */
public record CreateCategoryRequest(
        @Schema(description = "Display name of the custom category.", example = "Frutas")
        @NotBlank @Size(max = 60) String name,

        @Schema(description = "Ionicons name for the chip/icon.", example = "paw-outline")
        @Size(max = 40) String icon,

        @Schema(description = "Hex color for the chip.", example = "#F5A623")
        @Size(max = 9) String color,

        @Schema(description = "Slug of the taxonomy GROUP to nest under (null = standalone).",
                example = "pet")
        @Size(max = 60) String parentGroupSlug
) {}
