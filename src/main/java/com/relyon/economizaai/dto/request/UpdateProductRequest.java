package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.ProductCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateProductRequest(
        @NotBlank @Size(max = 255) String normalizedName,
        @Size(max = 100) String genericName,
        @Size(max = 100) String brand,
        ProductCategory category,
        @Size(max = 10) String unit,
        @DecimalMin(value = "0.0") BigDecimal packSize,
        @Size(max = 10) String packUnit
) {}
