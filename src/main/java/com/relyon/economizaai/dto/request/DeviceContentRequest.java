package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The app's on-device retry for a receipt the server left in NEEDS_DEVICE_FETCH:
 * it fetched the nota's own SEFAZ page (its residential/mobile IP is accepted
 * where our datacenter server is blocked) and reposts the raw body. The chave and
 * qrPayload are already on the stored receipt — only the fetched content is needed.
 */
public record DeviceContentRequest(
        @Schema(description = "The raw HTML/XML the app fetched from the receipt's SEFAZ URL on-device "
                + "(follow redirects). Must be the page for THIS nota — it has to carry the chave.")
        @NotBlank @Size(max = 4_000_000) String rawContent
) {}
