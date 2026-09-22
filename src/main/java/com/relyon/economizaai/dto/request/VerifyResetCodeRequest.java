package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyResetCodeRequest(
        @Schema(description = "Email of the account being reset.", example = "maria@example.com")
        @NotBlank @Email String email,
        @Schema(description = "6-digit code from the password-reset email.", example = "123456")
        @NotBlank @Pattern(regexp = "\\d{6}", message = "code must be 6 digits") String code
) {}
