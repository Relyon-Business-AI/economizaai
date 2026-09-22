package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse.BrandStrength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SetBrandPreferenceRequest(
        @NotBlank String brand,
        @NotNull BrandStrength strength
) {}
