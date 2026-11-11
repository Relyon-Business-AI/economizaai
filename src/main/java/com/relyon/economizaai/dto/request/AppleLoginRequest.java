package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.Platform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record AppleLoginRequest(
        @Schema(description = "Apple identity token (JWT) obtained natively by the mobile app via Sign in with Apple. "
                + "Verified server-side against Apple's JWKS.")
        @NotBlank String identityToken,

        @Schema(description = "Display name. Apple only returns the user's name on the FIRST authorization, so the app "
                + "must forward it here; it can be null on subsequent sign-ins.")
        String name,

        @Schema(description = "Optional client platform (WEB / ANDROID / IOS). Recorded as the last-login "
                + "platform. Unknown/absent values are ignored.", example = "IOS")
        Platform platform,
        @Schema(description = "Optional marketing attribution from the landing URL — applied only when THIS call "
                + "creates the account (first social login). Ignored for returning users.")
        AttributionInfo attribution,
        @Schema(description = "Optional referral code of the person who invited this user. Applied only when THIS "
                + "call creates the account; silently ignored when invalid.", example = "ABC2345")
        String referralCode
) {

    /** Back-compat: social login without a referral code. */
    public AppleLoginRequest(String identityToken, String name, Platform platform, AttributionInfo attribution) {
        this(identityToken, name, platform, attribution, null);
    }

    /** Back-compat: social login without marketing attribution. */
    public AppleLoginRequest(String identityToken, String name, Platform platform) {
        this(identityToken, name, platform, null, null);
    }
}
