package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateHomeLocationRequest(
        @Schema(description = "Decimal degrees, WGS84. Used as the reference point for radius-based " +
                "best-markets and community-promo filtering.", example = "-30.0277")
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @Schema(description = "Decimal degrees, WGS84.", example = "-51.2287")
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude
) {}
