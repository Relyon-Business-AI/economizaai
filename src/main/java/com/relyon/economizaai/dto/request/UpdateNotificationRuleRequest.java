package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.NotificationChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

/**
 * Partial update of an existing rule (PATCH). Every field is optional — only
 * non-null fields are applied. The common use is toggling {@code active} to
 * enable/disable a rule (including system defaults).
 */
public record UpdateNotificationRuleRequest(
        @Schema(description = "Enable/disable the rule.")
        Boolean active,

        @DecimalMin(value = "0.01") BigDecimal thresholdPrice,

        @DecimalMin(value = "0.1") Double radiusKm,

        @Min(0) Integer leadTimeDays,

        NotificationChannel channel
) {}
