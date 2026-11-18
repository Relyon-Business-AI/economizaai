package com.relyon.economizaai.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Items to plan plus the optional route rules. Every rule is independent and null means
 * "no limit": {@code maxDistanceKm} drops stores farther than that from the shopper,
 * {@code maxStops} caps how many stores the plan sends them to, and
 * {@code minSavingPerStop} is how much (R$) an extra stop must save to be worth the trip.
 * {@code latitude}/{@code longitude} (all-or-nothing) are the device's current position;
 * without them the distance is measured from the "perto de você" origin.
 */
public record OptimizeShoppingListRequest(
        @NotEmpty
        @Valid
        List<Item> items,
        @DecimalMin("0.1") @DecimalMax("200")
        BigDecimal maxDistanceKm,
        @Min(1) @Max(10)
        Integer maxStops,
        @DecimalMin("0")
        BigDecimal minSavingPerStop,
        @DecimalMin("-90") @DecimalMax("90")
        BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180")
        BigDecimal longitude
) {
    /** A request with no route rules: every item goes to its cheapest known store. */
    public OptimizeShoppingListRequest(List<Item> items) {
        this(items, null, null, null, null, null);
    }

    public record Item(
            @NotNull UUID productId,
            @NotNull
            @DecimalMin(value = "0.001", message = "{validation.quantity.positive}")
            BigDecimal quantity
    ) {}
}
