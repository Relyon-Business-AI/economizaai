package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Admin grant of a chain (cnpj_root = first 8 CNPJ digits) to a MERCHANT user. */
public record GrantMerchantAccessRequest(
        @Schema(description = "First 8 digits of the chain's CNPJ.", example = "93015006",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "\\d{8}") String cnpjRoot
) {}
