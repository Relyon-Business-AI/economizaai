package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * The app's response for a receipt the server left in NEEDS_DEVICE_FETCH (its
 * state blocks our datacenter IP). Two outcomes:
 *
 * <ul>
 *   <li><b>Fetched</b> — the app read the nota's own SEFAZ page on its accepted
 *       (residential/mobile) IP and reposts the raw body in {@code rawContent}.</li>
 *   <li><b>Gave up</b> — the app couldn't fetch it on-device either (complex
 *       portal, captcha, timeout): {@code unavailable=true}. The server then runs
 *       the next fallback layer (paid by-chave lookup) IMMEDIATELY, instead of
 *       waiting for the device-fetch sweeper — so the user gets a terminal status
 *       fast rather than a false "failed" while a background rescue is pending.</li>
 * </ul>
 *
 * <p>The chave and qrPayload are already on the stored receipt — only the outcome
 * is needed. Exactly one of {@code rawContent} / {@code unavailable} must be set.
 */
public record DeviceContentRequest(
        @Schema(description = "The raw HTML/XML the app fetched from the receipt's SEFAZ URL on-device "
                + "(follow redirects). Must be the page for THIS nota — it has to carry the chave. "
                + "Omit when unavailable=true.")
        @Size(max = 4_000_000) String rawContent,

        @Schema(description = "True when the app could NOT fetch the nota on-device either — the server "
                + "runs the next fallback layer immediately instead of leaving the row for the sweeper.")
        boolean unavailable
) {
    public boolean hasContent() {
        return rawContent != null && !rawContent.isBlank();
    }
}
