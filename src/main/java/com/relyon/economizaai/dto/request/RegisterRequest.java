package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.Platform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Schema(description = "Display name shown across the app and on shared household views.", example = "Maria Silva")
        @NotBlank String name,
        @Schema(description = "Login identifier. Must be unique across the system.", example = "maria@example.com")
        @NotBlank @Email String email,
        @Schema(description = "8+ chars. Plain text on the wire — sent over HTTPS, hashed (BCrypt) at rest.")
        @NotBlank @Size(min = 8, max = 100) String password,
        @Schema(description = "Version string of the Terms of Use the user accepted (fetch current via GET /legal/terms). " +
                "Backend rejects stale versions with 400.", example = "1.0")
        @NotBlank String acceptedTermsVersion,
        @Schema(description = "Version string of the Privacy Policy the user accepted (fetch current via GET /legal/privacy-policy).",
                example = "1.0")
        @NotBlank String acceptedPrivacyVersion,
        @Schema(description = "Optional client platform (WEB / ANDROID / IOS). Recorded as the registration "
                + "platform. Unknown/absent values are ignored.", example = "ANDROID")
        Platform platform,
        @Schema(description = "Optional marketing attribution read off the landing URL — utm params, click id, "
                + "referrer. Forwarded by the web FE on the first signup; omit on app signups.")
        AttributionInfo attribution
) {

    /** Back-compat: signup without marketing attribution (app clients, tests). */
    public RegisterRequest(String name, String email, String password,
                           String acceptedTermsVersion, String acceptedPrivacyVersion, Platform platform) {
        this(name, email, password, acceptedTermsVersion, acceptedPrivacyVersion, platform, null);
    }

    @AssertTrue(message = "Você deve aceitar a versão atual dos Termos de Uso e da Política de Privacidade")
    public boolean isLegalAcceptanceValid() {
        return acceptedTermsVersion != null && !acceptedTermsVersion.isBlank()
                && acceptedPrivacyVersion != null && !acceptedPrivacyVersion.isBlank();
    }
}
