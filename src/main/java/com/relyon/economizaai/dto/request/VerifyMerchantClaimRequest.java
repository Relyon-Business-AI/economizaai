package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record VerifyMerchantClaimRequest(
        @Schema(description = "6-digit code sent to the company e-mail registered at the Receita.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String code
) {}
