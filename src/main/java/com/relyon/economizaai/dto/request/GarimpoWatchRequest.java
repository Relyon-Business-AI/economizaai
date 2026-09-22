package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Admin payload for a garimpo watch. At least one of {@code targetPrice} /
 * {@code minDiscountPercent} must be set (service-enforced — meeting either one is
 * a hit). {@code marketplace} defaults to "mercadolivre"; {@code active} to true.
 */
public record GarimpoWatchRequest(
        @NotBlank @Size(max = 255) String searchTerm,
        @Size(max = 40) String marketplace,
        @DecimalMin("0.01") BigDecimal targetPrice,
        @Min(1) @Max(99) Integer minDiscountPercent,
        Boolean active) {
}
