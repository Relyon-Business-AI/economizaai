package com.relyon.economizai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Submit a receipt whose SEFAZ page the CLIENT already fetched on-device. Some
 * state portals (e.g. Pernambuco) serve the nota to residential/mobile IPs but
 * block our datacenter server — so the app, running on the user's phone, reads
 * its own nota's page and sends the raw content here. The backend parses it with
 * the exact same per-UF parser as a server-side fetch (no scraping from our IP).
 */
public record PrefetchedReceiptRequest(
        @Schema(description = "Exactly what the QR scanner returned — same shapes as POST /receipts.",
                example = "http://nfce.sefaz.pe.gov.br/nfce/consulta?p=26260942591651264205650010000777891671062850|3|1")
        @NotBlank @Size(max = 4000) String qrPayload,

        @Schema(description = "The raw HTML/XML body the app fetched from the QR's SEFAZ URL on the device "
                + "(follow redirects). Must be the page for THIS nota — it has to carry the chave de acesso.")
        @NotBlank @Size(max = 4_000_000) String rawContent
) {}
