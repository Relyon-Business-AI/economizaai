package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.ReceiptItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single purchased line item, flattened across receipts with the parent
 * receipt's market + date carried inline. Returned by {@code GET /items}.
 *
 * <p>Same item facts as {@link ReceiptItemResponse}, plus the receipt context
 * (receiptId / market / purchasedAt) so the FE can render and deep-link a
 * cross-receipt list without a second fetch.
 */
public record PurchasedItemResponse(
        UUID itemId,
        UUID productId,
        String category,
        String globalCategory,
        String displayDescription,
        String rawDescription,
        String friendlyDescription,
        String ean,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        BigDecimal paidUnitPrice,
        BigDecimal paidTotalPrice,
        boolean promotional,
        boolean nfcePromoFlag,
        UUID receiptId,
        String marketName,
        String marketFriendlyName,
        String marketCnpj,
        LocalDateTime purchasedAt
) {
    public static PurchasedItemResponse from(ReceiptItem item) {
        return from(item, (String) null);
    }

    /**
     * @param overrideLabel the household's corrected category label (enum name or
     *                      custom-category name), shown in place of the product's
     *                      global category when present.
     */
    public static PurchasedItemResponse from(ReceiptItem item, String overrideLabel) {
        var friendly = item.getFriendlyDescription();
        var display = friendly != null && !friendly.isBlank() ? friendly : item.getRawDescription();
        var product = item.getProduct();
        var receipt = item.getReceipt();
        var globalCategory = product != null && product.getCategory() != null ? product.getCategory().name() : null;
        return new PurchasedItemResponse(
                item.getId(),
                product != null ? product.getId() : null,
                overrideLabel != null ? overrideLabel : globalCategory,
                globalCategory,
                display,
                item.getRawDescription(),
                friendly,
                item.getEan(),
                item.getQuantity(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                item.getPaidUnitPrice(),
                item.getPaidTotalPrice(),
                item.isPromotional(),
                item.isNfcePromoFlag(),
                receipt.getId(),
                receipt.getMarketName(),
                receipt.getMarketName(),
                receipt.getCnpjEmitente(),
                receipt.getIssuedAt());
    }

    /** Copy with the household's custom display name applied; leaves the original {@code marketName} intact. */
    public PurchasedItemResponse withMarketFriendlyName(String marketFriendlyName) {
        return new PurchasedItemResponse(itemId, productId, category, globalCategory, displayDescription, rawDescription,
                friendlyDescription, ean, quantity, unit, unitPrice, totalPrice, paidUnitPrice, paidTotalPrice,
                promotional, nfcePromoFlag, receiptId, marketName, marketFriendlyName, marketCnpj, purchasedAt);
    }
}
