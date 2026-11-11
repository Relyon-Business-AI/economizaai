package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmEmailChangeRequest(
        @Schema(description = "6-digit code sent to the NEW e-mail. Expires in 30 minutes.", example = "123456")
        @NotBlank @Pattern(regexp = "\\d{6}", message = "{validation.code.six-digits}") String code,
        @Schema(description = "Optional: this device's refresh token. When sent, its session stays signed in "
                + "while every other session is revoked; when omitted, all sessions are revoked.")
        String refreshToken
) {}
