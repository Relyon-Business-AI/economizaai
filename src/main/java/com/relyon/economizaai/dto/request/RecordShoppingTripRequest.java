package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.dto.response.ShoppingPlanResponse.PlanItem.PriceSource;
import com.relyon.economizaai.model.enums.ShoppingTripItemStatus;
import com.relyon.economizaai.model.enums.ShoppingTripOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A finished shopping trip, sent once by the app: the plan it started from and what happened at
 * each stop. {@code clientTripId} is generated on the device and makes the call idempotent.
 * Counters and flags the app leaves out count as zero / false.
 *
 * <p>Every number is capped at what its column holds, so a value that cannot be stored is a 400
 * (the app drops the record) rather than a 500 (the app would keep resending it).
 */
public record RecordShoppingTripRequest(
        @NotBlank @Size(max = 64) String clientTripId,
        UUID shoppingListId,
        @NotNull OffsetDateTime startedAt,
        @NotNull OffsetDateTime finishedAt,
        @NotNull ShoppingTripOutcome outcome,
        @DecimalMin("0") @DecimalMax("99999.9") BigDecimal maxDistanceKm,
        @Min(1) Integer maxStops,
        @DecimalMin("0") @DecimalMax(StorageLimits.MONEY) BigDecimal minSavingPerStop,
        @NotNull @DecimalMin("0") @DecimalMax(StorageLimits.MONEY) BigDecimal plannedTotal,
        @NotNull @Min(0) Integer plannedStopCount,
        @Min(0) Integer unpricedItemCount,
        @DecimalMin("0") @DecimalMax(StorageLimits.MONEY) BigDecimal skippedSavingTotal,
        @Min(0) Integer priceLookupCount,
        @NotEmpty @Size(max = 20) @Valid List<Stop> stops
) {
    /**
     * The trip's totals (each stop's predicted total, the bought basket) are sums of its item
     * lines, so the lines together have to fit a money column too.
     */
    @AssertTrue(message = "{validation.shopping-trip.items-total}")
    public boolean isItemsTotalStorable() {
        if (stops == null) return true;
        var itemsTotal = stops.stream()
                .filter(Objects::nonNull)
                .flatMap(stop -> stop.items() == null ? Stream.<Item>empty() : stop.items().stream())
                .filter(Objects::nonNull)
                .map(Item::largestLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return itemsTotal.compareTo(new BigDecimal(StorageLimits.MONEY)) <= 0;
    }

    public record Stop(
            @NotBlank @Size(max = 14) String marketCnpj,
            @Size(max = 255) String marketName,
            @DecimalMin("0") @DecimalMax("99999.999") BigDecimal distanceKm,
            @NotNull Boolean concluded,
            OffsetDateTime concludedAt,
            UUID receiptId,
            @DecimalMin("0") @DecimalMax(StorageLimits.MONEY) BigDecimal receiptPaidTotal,
            @DecimalMin("0") @DecimalMax(StorageLimits.MONEY) BigDecimal receiptPredictedTotal,
            @NotNull @Size(max = 500) @Valid List<Item> items
    ) {}

    public record Item(
            @NotNull UUID productId,
            @NotNull @DecimalMin("0.001") @DecimalMax(StorageLimits.QUANTITY) BigDecimal quantity,
            @DecimalMin("0") @DecimalMax(StorageLimits.MONEY) BigDecimal plannedUnitPrice,
            PriceSource priceSource,
            @NotNull ShoppingTripItemStatus status,
            Boolean inReceipt,
            @DecimalMin("0") @DecimalMax(StorageLimits.MONEY) BigDecimal paidUnitPrice,
            @Min(0) Integer plannedStopPosition,
            Boolean addedDuringTrip
    ) {
        /** The line at its dearest known price (predicted or paid), in cents; zero without a price. */
        BigDecimal largestLineTotal() {
            var unitPrice = Stream.of(plannedUnitPrice, paidUnitPrice)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            if (unitPrice == null || quantity == null) return BigDecimal.ZERO;
            return unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
        }
    }
}
