package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One merchant promo (manual create/update and JSON batch rows share this shape). */
public record MerchantPromoRequest(
        @Schema(description = "Chain (first 8 CNPJ digits). Optional when the account manages a single chain.")
        @Pattern(regexp = "\\d{8}") String cnpjRoot,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "7891000100103")
        @NotBlank @Pattern(regexp = "\\d{8,14}") String ean,
        @Size(max = 255) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "4.99")
        @NotNull BigDecimal promoPrice,
        BigDecimal regularPrice,
        @NotNull LocalDate startsAt,
        @NotNull LocalDate endsAt
) {}
