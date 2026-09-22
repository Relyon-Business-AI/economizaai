package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.UnidadeFederativa;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChaveExtractionResponse(
        @Schema(description = "The 44-digit chave de acesso read from the photo. Show it to the user "
                + "for confirmation, then submit via POST /api/v1/receipts with this value as qrPayload.",
                example = "43260412345678000190650010000123451123456782")
        String chaveAcesso,
        @Schema(description = "UF derived from the chave prefix.")
        UnidadeFederativa uf
) {}
