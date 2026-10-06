package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Self-serve "sou este mercado" claim — any authenticated user can submit. */
public record SubmitMerchantClaimRequest(
        @Schema(description = "14-digit CNPJ of one store of the chain.", example = "93015006000101",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "\\d{14}") String cnpj,
        @Size(max = 255) String companyName,
        @Size(max = 20) String contactPhone
) {}
