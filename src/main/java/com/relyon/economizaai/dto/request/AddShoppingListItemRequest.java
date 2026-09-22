package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record AddShoppingListItemRequest(
        @Schema(description = "Canonical Product UUID. Either this or freeText must be set.")
        UUID productId,
        @Schema(description = "Free-text entry when no canonical product yet.", example = "azeite extra virgem")
        @Size(max = 255) String freeText,
        @Schema(description = "Quantity. Defaults to 1.", example = "2")
        @DecimalMin(value = "0.001") BigDecimal quantity
) {}
