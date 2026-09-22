package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateShoppingListRequest(
        @Schema(description = "Display name for the list (e.g. 'Compra da semana').", example = "Compra da semana")
        @NotBlank @Size(max = 120) String name,
        @Schema(description = "Optional initial items. Add more later via POST /shopping-lists/{id}/items.")
        @Valid List<Item> items
) {
    public record Item(
            @Schema(description = "Product UUID. Either this or freeText must be present.")
            UUID productId,
            @Schema(description = "Free-text entry when there's no canonical product yet.", example = "papel higiênico")
            @Size(max = 255) String freeText,
            @Schema(description = "Quantity (defaults to 1).")
            @DecimalMin(value = "0.001") BigDecimal quantity
    ) {}
}
