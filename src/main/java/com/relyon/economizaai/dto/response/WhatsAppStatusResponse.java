package com.relyon.economizaai.dto.response;

/**
 * WhatsApp opt-in state for the Settings screen. {@code confirmLink}/{@code reportLink}
 * are wa.me deep-links (carry the user's token) — the FE opens confirmLink to opt in and
 * reportLink to pull a report on demand. {@code maskedNumber} is "****1234" or null.
 */
public record WhatsAppStatusResponse(
        boolean optIn,
        boolean weeklyReportEnabled,
        String maskedNumber,
        String confirmLink,
        String reportLink) {}
