package com.relyon.economizaai.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OptimizeShoppingListRequest(
        @NotEmpty
        @Valid
        List<Item> items
) {
    public record Item(
            @NotNull UUID productId,
            @NotNull
            @DecimalMin(value = "0.001", message = "quantity must be > 0")
            BigDecimal quantity
    ) {}
}
