package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetProductBrandRequest(
        @Schema(description = "Brand display name. Persists onto Product.brand.", example = "Tio João")
        @NotBlank @Size(max = 100) String brand
) {}
