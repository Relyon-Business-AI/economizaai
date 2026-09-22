package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAliasRequest(
        @Schema(
                description = "The raw, unnormalized item text exactly as it appears on a NFC-e " +
                        "(uppercase, abbreviations, retailer-specific). Will be normalized " +
                        "server-side and used to auto-link unmatched receipt items to this product.",
                example = "ARROZ TIO J TP1 5KG"
        )
        @NotBlank @Size(max = 500) String rawDescription
) {}
