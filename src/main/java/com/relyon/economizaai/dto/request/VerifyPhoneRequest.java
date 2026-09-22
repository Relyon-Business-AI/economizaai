package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyPhoneRequest(
        @Schema(description = "6-digit OTP sent to the user's phone.", example = "123456")
        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "must be a 6-digit code")
        String code
) {}
