package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Create (or upsert) a notification rule. Re-posting the same
 * {@code (type, productId)} updates the existing rule. Which fields are
 * required depends on the type and is validated server-side:
 * PRICE_DROP needs productId + thresholdPrice; STOCKOUT needs
 * productId; BUDGET needs thresholdPrice.
 */
public record CreateNotificationRuleRequest(
        @Schema(description = "Rule type.", requiredMode = Schema.RequiredMode.REQUIRED, example = "PRICE_DROP")
        @NotNull NotificationType type,

        @Schema(description = "Canonical product UUID (required for product-scoped rules).")
        UUID productId,

        @Schema(description = "Price/spend threshold in R$ (required for PRICE_DROP, BUDGET).", example = "12.90")
        @DecimalMin(value = "0.01") BigDecimal thresholdPrice,

        @Schema(description = "Optional radius (km) from the user's home; null = anywhere.", example = "5")
        @DecimalMin(value = "0.1") Double radiusKm,

        @Schema(description = "Replenishment lead time in days (STOCKOUT only; defaults to 3).", example = "3")
        @Min(0) Integer leadTimeDays,

        @Schema(description = "Optional channel override (PUSH/EMAIL/...). Null uses your per-type preference.")
        NotificationChannel channel,

        @Schema(description = "Whether the rule is active. Defaults to true when omitted.")
        Boolean active
) {}
