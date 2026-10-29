package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.UnidadeFederativa;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChaveExtractionResponse(
        @Schema(description = "The 44-digit chave de acesso read from the photo. Show it to the user "
                + "for confirmation, then submit via POST /api/v1/receipts with this value as qrPayload.",
                example = "43260412345678000190650010000123451123456782")
        String chaveAcesso,
        @Schema(description = "UF derived from the chave prefix.")
        UnidadeFederativa uf,
        @Schema(description = "Full signed QR payload when a QR code was readable in the photo. "
                + "PREFER this as qrPayload on submit — the signed URL works for EVERY state, "
                + "while a bare chave only works where public consult-by-chave exists.")
        String qrPayload,
        @Schema(description = "True when the chave is a BEST-EFFORT read: its structure (UF, model, "
                + "year-month) is valid but the mod-11 check digit did NOT close, so one or more "
                + "digits were likely misread. The FE must warn the user to review every digit "
                + "against the printed note and fix before submitting.")
        boolean uncertain
) {
    public ChaveExtractionResponse(String chaveAcesso, UnidadeFederativa uf) {
        this(chaveAcesso, uf, null, false);
    }

    public ChaveExtractionResponse(String chaveAcesso, UnidadeFederativa uf, String qrPayload) {
        this(chaveAcesso, uf, qrPayload, false);
    }
}
