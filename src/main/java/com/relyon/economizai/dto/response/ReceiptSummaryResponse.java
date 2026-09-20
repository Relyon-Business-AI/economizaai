package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.enums.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight per-row payload for {@code GET /receipts} and the dashboard's
 * {@code recentReceipts}. Mirrors the two-total contract of {@link ReceiptResponse}:
 *
 * <ul>
 *   <li>{@code totalAmount} — the original NF total as printed on the
 *       receipt. Audit reference. Never changes after ingestion.</li>
 *   <li>{@code householdTotalAmount} — sum of the items the household kept
 *       (excluded items removed). This is what they actually spent and the
 *       value the FE should display by default in lists / spend totals.</li>
 *   <li>{@code approxTaxTotal} — IBPT-table approximate tax (federal +
 *       estadual) embedded in the prices, when the merchant declared it.
 *       Null when the receipt's HTML didn't carry the IBPT line.</li>
 * </ul>
 *
 * <p>For PENDING_CONFIRMATION receipts no items are excluded yet, so both
 * totals match. They diverge after the user confirms with exclusions.
 */
public record ReceiptSummaryResponse(
        UUID id,
        String marketName,
        String marketFriendlyName,
        LocalDateTime issuedAt,
        BigDecimal totalAmount,
        BigDecimal householdTotalAmount,
        BigDecimal discountTotal,
        BigDecimal approxTaxTotal,
        int itemCount,
        ReceiptStatus status
) {
    public static ReceiptSummaryResponse from(Receipt receipt) {
        var householdTotal = receipt.getItems().stream()
                .filter(item -> !item.isExcluded() && !item.isExcludedFromPersonal())
                .map(ReceiptItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReceiptSummaryResponse(
                receipt.getId(),
                receipt.getMarketName(),
                receipt.getMarketName(),
                receipt.getIssuedAt(),
                receipt.getTotalAmount(),
                householdTotal,
                receipt.getDiscountTotal(),
                approxTaxTotal(receipt),
                receipt.getItems().size(),
                receipt.getStatus()
        );
    }

    /** Copy with the household's custom display name applied; leaves the original {@code marketName} intact. */
    public ReceiptSummaryResponse withMarketFriendlyName(String marketFriendlyName) {
        return new ReceiptSummaryResponse(id, marketName, marketFriendlyName, issuedAt,
                totalAmount, householdTotalAmount, discountTotal, approxTaxTotal, itemCount, status);
    }

    private static BigDecimal approxTaxTotal(Receipt receipt) {
        var fed = receipt.getApproxTaxFederal();
        var est = receipt.getApproxTaxEstadual();
        if (fed == null && est == null) return null;
        return (fed == null ? BigDecimal.ZERO : fed).add(est == null ? BigDecimal.ZERO : est);
    }
}
