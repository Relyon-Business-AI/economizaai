package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitReceiptRequest(
        @Schema(
                description = """
                        Whatever the camera scanned from the NFC-e QR code. Three accepted shapes,
                        all parsed into the 44-digit chave de acesso server-side:

                          1. Full SVRS landing URL: https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=<chave>|3|1
                          2. Full dfe-portal URL: https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce?p=<chave>|3|1
                          3. The raw pipe payload: <44-digit-chave>|3|1
                          4. SC SecurityVerify URL: https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=...
                          5. The bare 44-digit chave de acesso

                        The string is opaque to the FE — pass exactly what your QR scanner returned.
                        """,
                example = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=43260583261420003255656140000288561445164522|3|1"
        )
        @NotBlank @Size(max = 4000) String qrPayload
) {}
