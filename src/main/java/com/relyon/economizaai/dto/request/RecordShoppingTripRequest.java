package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.dto.response.ShoppingPlanResponse.PlanItem.PriceSource;
import com.relyon.economizaai.model.enums.ShoppingTripItemStatus;
import com.relyon.economizaai.model.enums.ShoppingTripOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A finished shopping trip, sent once by the app: the plan it started from and what happened at
 * each stop. {@code clientTripId} is generated on the device and makes the call idempotent.
 * Counters and flags the app leaves out count as zero / false.
 */
public record RecordShoppingTripRequest(
        @NotBlank @Size(max = 64) String clientTripId,
        UUID shoppingListId,
        @NotNull OffsetDateTime startedAt,
        @NotNull OffsetDateTime finishedAt,
        @NotNull ShoppingTripOutcome outcome,
        @DecimalMin("0") BigDecimal maxDistanceKm,
        @Min(1) Integer maxStops,
        @DecimalMin("0") BigDecimal minSavingPerStop,
        @NotNull @DecimalMin("0") BigDecimal plannedTotal,
        @NotNull @Min(0) Integer plannedStopCount,
        @Min(0) Integer unpricedItemCount,
        @DecimalMin("0") BigDecimal skippedSavingTotal,
        @Min(0) Integer priceLookupCount,
        @NotEmpty @Size(max = 20) @Valid List<Stop> stops
) {
    public record Stop(
            @NotBlank @Size(max = 14) String marketCnpj,
            @Size(max = 255) String marketName,
            @DecimalMin("0") BigDecimal distanceKm,
            @NotNull Boolean concluded,
            OffsetDateTime concludedAt,
            UUID receiptId,
            BigDecimal receiptPaidTotal,
            BigDecimal receiptPredictedTotal,
            @NotNull @Size(max = 500) @Valid List<Item> items
    ) {}

    public record Item(
            @NotNull UUID productId,
            @NotNull @DecimalMin("0.001") BigDecimal quantity,
            @DecimalMin("0") BigDecimal plannedUnitPrice,
            PriceSource priceSource,
            @NotNull ShoppingTripItemStatus status,
            Boolean inReceipt,
            @DecimalMin("0") BigDecimal paidUnitPrice,
            @Min(0) Integer plannedStopPosition,
            Boolean addedDuringTrip
    ) {}
}
