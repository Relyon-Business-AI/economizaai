package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMarketChainRequest(
        @NotBlank @Size(min = 8, max = 8) String cnpjRoot,
        @NotBlank String name
) {}
