package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateMarketChainRequest(@NotBlank String name) {}
