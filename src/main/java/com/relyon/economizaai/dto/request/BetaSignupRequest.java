package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A "I want to be a beta tester" signup from the app. Public — no auth — so
 * anyone interested can raise their hand; rate-limited by IP to keep spam out.
 * No free-text message (unlike {@link ContactRequest}); this is lead capture,
 * so we only take the name + email (+ optional phone) and email it to our beta inbox.
 */
public record BetaSignupRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 30) String phone) {
}
