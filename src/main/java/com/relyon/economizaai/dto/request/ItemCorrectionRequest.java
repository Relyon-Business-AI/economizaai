package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ItemCorrectionRequest(
        @NotBlank @Size(max = 300) String hint
) {}
