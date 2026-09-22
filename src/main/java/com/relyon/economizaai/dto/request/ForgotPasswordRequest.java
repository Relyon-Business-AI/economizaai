package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
        @Schema(description = "Email of the account to recover. Always returns 200, even when the email isn't " +
                "registered, to avoid leaking which addresses exist.",
                example = "maria@example.com")
        @NotBlank @Email String email
) {}
