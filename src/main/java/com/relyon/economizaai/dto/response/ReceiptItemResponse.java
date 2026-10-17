package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.ReceiptItem;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line item. Prices are as-printed on the NFC-e (shelf price); {@code paidUnitPrice}/
 * {@code paidTotalPrice} carry the user-entered "price actually paid" when the line was
 * bought on promotion, and {@code promotional} is the derived flag ({@code paidTotalPrice}
 * present and below {@code totalPrice}) the FE keys the discount badge off.
 */
public record ReceiptItemResponse(
        UUID id,
        UUID productId,
        Integer lineNumber,
        String rawDescription,
        String friendlyDescription,
        String displayDescription,
        String ean,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        BigDecimal paidUnitPrice,
        BigDecimal paidTotalPrice,
        boolean promotional,
        boolean excluded,
        boolean excludedFromPersonal,
        boolean nfcePromoFlag,
        String category,
        boolean categorySuggested,
        // New two-level taxonomy (additive; `category` above keeps the legacy enum
        // name so the FE picker/icon logic is unchanged). categoryNodeLabel is the
        // localized fine-category name to DISPLAY; categoryNodeSlug its stable key.
        // Null when no node resolved OR a household override is in effect.
        String categoryNodeSlug,
        String categoryNodeLabel
) {
    public static ReceiptItemResponse from(ReceiptItem item) {
        return from(item, null, false, null, null);
    }

    public static ReceiptItemResponse from(ReceiptItem item, String overrideLabel) {
        return from(item, overrideLabel, false, null, null);
    }

    public static ReceiptItemResponse from(ReceiptItem item, String overrideLabel, boolean suggested) {
        return from(item, overrideLabel, suggested, null, null);
    }

    /**
     * Category precedence: household override (explicit user choice) →
     * snapshot frozen at confirmation → live product category. The snapshot
     * keeps confirmed history stable while the product keeps improving for
     * future purchases.
     *
     * @param overrideLabel the household's corrected category label (global enum
     *                      name OR a custom-category name), shown in place of the
     *                      product's/snapshot category when present.
     * @param suggested     true when {@code overrideLabel} is a best-effort
     *                      PREVIEW (pending receipt, item not linked yet) rather
     *                      than a user choice or confirmed category — the FE
     *                      should render it as a suggestion.
     */
    public static ReceiptItemResponse from(ReceiptItem item, String overrideLabel, boolean suggested,
                                           String categoryNodeSlug, String categoryNodeLabel) {
        var friendly = item.getFriendlyDescription();
        var display = friendly != null && !friendly.isBlank() ? friendly : item.getRawDescription();
        var product = item.getProduct();
        var snapshot = item.getCategoryAtConfirmation() != null ? item.getCategoryAtConfirmation().name() : null;
        var liveCategory = product != null && product.getCategory() != null ? product.getCategory().name() : null;
        var category = overrideLabel != null ? overrideLabel
                : snapshot != null ? snapshot
                : liveCategory;
        // Um override REAL (escolha do usuário) vence o node. Mas no preview da revisão o
        // overrideLabel é só a categoria SUGERIDA (suggested=true) — aí o node (subcategoria da IA)
        // deve aparecer junto, não ser suprimido.
        var realOverride = overrideLabel != null && !suggested;
        var nodeSlug = realOverride ? null : categoryNodeSlug;
        var nodeLabel = realOverride ? null : categoryNodeLabel;
        return new ReceiptItemResponse(
                item.getId(),
                product != null ? product.getId() : null,
                item.getLineNumber(),
                item.getRawDescription(),
                friendly,
                display,
                item.getEan(),
                item.getQuantity(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                item.getPaidUnitPrice(),
                item.getPaidTotalPrice(),
                item.isPromotional(),
                item.isExcluded(),
                item.isExcludedFromPersonal(),
                item.isNfcePromoFlag(),
                category,
                suggested,
                nodeSlug,
                nodeLabel
        );
    }
}
