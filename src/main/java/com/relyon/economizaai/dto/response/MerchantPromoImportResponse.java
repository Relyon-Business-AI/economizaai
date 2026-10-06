package com.relyon.economizaai.dto.response;

import java.util.List;

/** Per-row import report (CSV/XLSX upload and JSON batch share this shape). */
public record MerchantPromoImportResponse(
        int received,
        int imported,
        int rejected,
        List<RejectedRow> errors) {

    /** {@code reasonKey} is the machine key; {@code reason} the localized message (FE renders reason). */
    public record RejectedRow(int line, String ean, String reasonKey, String reason) {}
}
