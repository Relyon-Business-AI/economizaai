package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateShoppingListRequest(
        @Schema(description = "New name for the list.")
        @NotBlank @Size(max = 120) String name
) {}
