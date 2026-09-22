package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.DigestFrequency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Updates the user's daily deals-digest delivery preferences (Phase C):
 * how often, and an optional wall-clock send-hour override (0-23,
 * America/Sao_Paulo). A null {@code sendHour} clears the override and lets the
 * scheduler infer the hour from shopping history (or fall back to the default).
 */
public record UpdateDigestPreferencesRequest(
        @NotNull DigestFrequency frequency,
        @Min(0) @Max(23) Integer sendHour
) {}
