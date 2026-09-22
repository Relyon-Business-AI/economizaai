package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Set a household-only custom display name for a market (by CNPJ).
 */
public record SetMarketNameRequest(
        @Schema(description = "Custom name shown only to your household for this market.", example = "Zaffari de casa")
        @NotBlank @Size(max = 255) String name
) {}
