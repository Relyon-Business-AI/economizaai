package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @Schema(description = "Email of the account being reset.", example = "maria@example.com")
        @NotBlank @Email String email,
        @Schema(description = "6-digit code from the password-reset email. Single-use, expires in 60 minutes.",
                example = "123456")
        @NotBlank @Pattern(regexp = "\\d{6}", message = "code must be 6 digits") String code,
        @Schema(description = "New password (8+ chars).")
        @NotBlank @Size(min = 8, max = 100) String newPassword
) {}
