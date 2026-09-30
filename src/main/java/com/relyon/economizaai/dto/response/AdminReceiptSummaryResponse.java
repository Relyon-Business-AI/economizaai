package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.UnidadeFederativa;

import java.time.LocalDateTime;

/**
 * Admin-facing row for {@code GET /admin/receipts}: the regular
 * {@link ReceiptSummaryResponse} plus the triage context an admin needs
 * before opening the detail — owning user, UF, when the row was created
 * (failed notas have no {@code issuedAt}) and, for FAILED_PARSE, the
 * machine reason + localized message.
 */
public record AdminReceiptSummaryResponse(
        ReceiptSummaryResponse receipt,
        AdminReceiptDetailResponse.Owner owner,
        UnidadeFederativa uf,
        LocalDateTime createdAt,
        String parseErrorReason,
        String parseErrorMessage
) {
    public static AdminReceiptSummaryResponse of(Receipt receipt, String parseErrorMessage) {
        var user = receipt.getUser();
        var owner = user == null ? null
                : new AdminReceiptDetailResponse.Owner(user.getId(), user.getName(), user.getEmail());
        return new AdminReceiptSummaryResponse(
                ReceiptSummaryResponse.from(receipt),
                owner,
                receipt.getUf(),
                BrazilClock.fromUtc(receipt.getCreatedAt()),
                receipt.getParseErrorReason(),
                parseErrorMessage);
    }
}
