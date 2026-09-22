package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.Platform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @Schema(description = "Google ID token (JWT) obtained natively by the mobile app via Google Sign-In. "
                + "Verified server-side against Google's JWKS.")
        @NotBlank String idToken,
        @Schema(description = "Optional client platform (WEB / ANDROID / IOS). Recorded as the last-login "
                + "platform. Unknown/absent values are ignored.", example = "ANDROID")
        Platform platform,
        @Schema(description = "Optional marketing attribution from the landing URL — applied only when THIS call "
                + "creates the account (first social login). Ignored for returning users.")
        AttributionInfo attribution
) {
    /** Back-compat: social login without marketing attribution. */
    public GoogleLoginRequest(String idToken, Platform platform) {
        this(idToken, platform, null);
    }
}
