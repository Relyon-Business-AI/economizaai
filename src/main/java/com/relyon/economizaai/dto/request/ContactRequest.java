package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A contact / feedback message from the app (doubt, criticism, suggestion,
 * praise). Public — no auth required — so anyone can reach us; rate-limited by IP
 * to keep spam out.
 */
public record ContactRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 30) String phone,
        @NotBlank @Size(max = 5000) String message) {
}
