package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReceiptResponse(
        UUID id,
        String chaveAcesso,
        // The raw scanned QR (SEFAZ URL). Surfaced so the app can re-fetch the nota
        // on-device when status is NEEDS_DEVICE_FETCH (a state that blocks our IP).
        String qrPayload,
        UnidadeFederativa uf,
        String cnpjEmitente,
        String marketName,
        String marketFriendlyName,
        String marketAddress,
        LocalDateTime issuedAt,
        BigDecimal totalAmount,
        BigDecimal householdTotalAmount,
        BigDecimal discountTotal,
        BigDecimal approxTaxFederal,
        BigDecimal approxTaxEstadual,
        BigDecimal approxTaxTotal,
        ReceiptStatus status,
        String parseErrorReason,
        String parseErrorMessage,
        LocalDateTime confirmedAt,
        LocalDateTime createdAt,
        List<ReceiptItemResponse> items
) {
    public static ReceiptResponse from(Receipt receipt) {
        return from(receipt, Map.of(), Map.of());
    }

    public static ReceiptResponse from(Receipt receipt, Map<UUID, String> categoryOverrides) {
        return from(receipt, categoryOverrides, Map.of());
    }

    /**
     * @param categoryOverrides    productId → the household's corrected category label
     *                             (enum name or custom-category name), applied in
     *                             place of the product's global category.
     * @param suggestionsByItemId  itemId → dictionary-suggested category for items
     *                             not yet linked to a product (review screen preview
     *                             of what confirm will apply).
     */
    public static ReceiptResponse from(Receipt receipt, Map<UUID, String> categoryOverrides,
                                       Map<UUID, String> suggestionsByItemId) {
        var householdTotal = receipt.getItems().stream()
                .filter(i -> !i.isExcluded())
                .map(i -> i.getTotalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReceiptResponse(
                receipt.getId(),
                receipt.getChaveAcesso(),
                receipt.getQrPayload(),
                receipt.getUf(),
                receipt.getCnpjEmitente(),
                receipt.getMarketName(),
                receipt.getMarketName(),
                receipt.getMarketAddress(),
                receipt.getIssuedAt(),
                receipt.getTotalAmount(),
                householdTotal,
                receipt.getDiscountTotal(),
                receipt.getApproxTaxFederal(),
                receipt.getApproxTaxEstadual(),
                approxTaxTotal(receipt),
                receipt.getStatus(),
                receipt.getParseErrorReason(),
                null,
                receipt.getConfirmedAt(),
                receipt.getCreatedAt(),
                receipt.getItems().stream()
                        .map(item -> {
                            var label = labelFor(item, categoryOverrides, suggestionsByItemId);
                            var suggested = label != null && item.getProduct() == null;
                            return ReceiptItemResponse.from(item, label, suggested);
                        })
                        .toList()
        );
    }

    /** Copy with the household's custom display name applied; leaves the original {@code marketName} intact. */
    public ReceiptResponse withMarketFriendlyName(String marketFriendlyName) {
        return new ReceiptResponse(
                id, chaveAcesso, qrPayload, uf, cnpjEmitente, marketName, marketFriendlyName, marketAddress,
                issuedAt, totalAmount, householdTotalAmount, discountTotal, approxTaxFederal, approxTaxEstadual,
                approxTaxTotal, status, parseErrorReason, parseErrorMessage, confirmedAt, createdAt, items);
    }

    /** Copy with the localized, user-showable failure message (Accept-Language resolved). */
    public ReceiptResponse withParseErrorMessage(String parseErrorMessage) {
        return new ReceiptResponse(
                id, chaveAcesso, qrPayload, uf, cnpjEmitente, marketName, marketFriendlyName, marketAddress,
                issuedAt, totalAmount, householdTotalAmount, discountTotal, approxTaxFederal, approxTaxEstadual,
                approxTaxTotal, status, parseErrorReason, parseErrorMessage, confirmedAt, createdAt, items);
    }

    /** Household override wins; for unlinked items fall back to the dictionary suggestion. */
    private static String labelFor(ReceiptItem item, Map<UUID, String> categoryOverrides,
                                   Map<UUID, String> suggestionsByItemId) {
        if (item.getProduct() == null) {
            return suggestionsByItemId.isEmpty() || item.getId() == null
                    ? null : suggestionsByItemId.get(item.getId());
        }
        return categoryOverrides.isEmpty() ? null : categoryOverrides.get(item.getProduct().getId());
    }

    private static BigDecimal approxTaxTotal(Receipt receipt) {
        var fed = receipt.getApproxTaxFederal();
        var est = receipt.getApproxTaxEstadual();
        if (fed == null && est == null) return null;
        return (fed == null ? BigDecimal.ZERO : fed).add(est == null ? BigDecimal.ZERO : est);
    }
}
