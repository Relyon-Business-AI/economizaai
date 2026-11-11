package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RequestEmailChangeRequest(
        @Schema(description = "New account e-mail. A 6-digit code is sent to it.", example = "maria.nova@example.com")
        @NotBlank @Email @Size(max = 255) String newEmail,
        @Schema(description = "The account's current password.")
        @NotBlank String currentPassword
) {}
