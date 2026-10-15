package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Patch a household custom category by id. All fields optional: a null field is left unchanged.
 *  Parent is only (re)set when a parent field is provided; providing BOTH parents is rejected. */
public record UpdateCategoryRequest(
        @Schema(description = "New display name (null = keep current).", example = "Frutas")
        @Size(max = 60) String name,

        @Schema(description = "Ionicons name for the chip/icon (null = keep current).", example = "paw-outline")
        @Size(max = 40) String icon,

        @Schema(description = "Hex color for the chip (null = keep current).", example = "#F5A623")
        @Size(max = 9) @Pattern(regexp = "^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
                message = "must be a hex color like #F5A623") String color,

        @Schema(description = "Slug of the built-in taxonomy CATEGORY to nest under.", example = "pet")
        @Size(max = 60) String parentGroupSlug,

        @Schema(description = "Id of a CUSTOM category of the household to nest under "
                + "(alternative to parentGroupSlug; at most one may be set).")
        UUID parentCustomCategoryId
) {}
