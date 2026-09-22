package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create a household custom category (e.g. "Frutas"). */
public record CreateCategoryRequest(
        @Schema(description = "Display name of the custom category.", example = "Frutas")
        @NotBlank @Size(max = 60) String name
) {}
