package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.Platform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @NotBlank String refreshToken,
        @Schema(description = "Optional client platform (WEB | ANDROID | IOS). Lets a session that only ever "
                + "renews (never logs in again) still tell us where the user is. Ignored on logout.")
        Platform platform
) {
    public RefreshTokenRequest(String refreshToken) {
        this(refreshToken, null);
    }
}
