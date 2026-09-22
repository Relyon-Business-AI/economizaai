package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SnoozeProductRequest(
        @NotNull
        @Min(1)
        Integer days
) {}
