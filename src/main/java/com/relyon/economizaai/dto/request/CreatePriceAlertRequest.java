package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Create/update an "avise-me quando" price-drop rule. Re-posting for a
 * product the user already watches updates the existing rule.
 */
public record CreatePriceAlertRequest(
        @Schema(description = "Canonical product UUID to watch.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID productId,

        @Schema(description = "Notify when an observed unit price is at or below this value (R$).", example = "5.99")
        @NotNull @DecimalMin(value = "0.01") BigDecimal thresholdPrice,

        @Schema(description = "Optional radius (km) from the user's home; null = anywhere in the network.", example = "5")
        @DecimalMin(value = "0.1") Double radiusKm,

        @Schema(description = "Whether the rule is active. Defaults to true when omitted.")
        Boolean active
) {}
