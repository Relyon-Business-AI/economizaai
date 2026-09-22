package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
        @Schema(description = "Account email the code was sent to.")
        @NotBlank @Email String email,
        @Schema(description = "6-digit code from the verification email. Single-use, expires in 24 hours, locks after 5 wrong attempts.")
        @NotBlank String code
) {}
