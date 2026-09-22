package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.ShoppingList;
import com.relyon.economizaai.model.ShoppingListItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ShoppingListResponse(
        UUID id,
        String name,
        UUID createdByUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int totalItems,
        int checkedItems,
        List<Item> items
) {
    public record Item(
            UUID id,
            UUID productId,
            String productName,
            String friendlyDescription,
            String freeText,
            String displayName,
            BigDecimal quantity,
            int position,
            boolean checked,
            LocalDateTime checkedAt
    ) {
        /**
         * @param householdAlias  the household's explicit rename of the item's product
         *                        (household_product_aliases), null when never renamed or
         *                        when the item has no linked product. Surfaced verbatim as
         *                        {@code friendlyDescription}.
         * @param productFriendly the household's last friendly name for this product from a
         *                        confirmed receipt (user-typed or inherited); a display-only
         *                        fallback used for {@code displayName} when there's no explicit
         *                        alias, so the list shows a human name instead of the raw
         *                        product name. Not surfaced as {@code friendlyDescription}.
         */
        public static Item from(ShoppingListItem i, String householdAlias, String productFriendly) {
            var product = i.getProduct();
            var productName = product != null ? product.getNormalizedName() : null;
            var hasAlias = householdAlias != null && !householdAlias.isBlank();
            var hasProductFriendly = productFriendly != null && !productFriendly.isBlank();
            // displayName precedence: explicit rename → the household's own receipt name
            // → raw normalized product name → free text.
            var display = hasAlias ? householdAlias
                    : hasProductFriendly ? productFriendly
                    : productName != null ? productName
                    : i.getFreeText();
            return new Item(
                    i.getId(),
                    product != null ? product.getId() : null,
                    productName,
                    hasAlias ? householdAlias : null,
                    i.getFreeText(),
                    display,
                    i.getQuantity(),
                    i.getPosition(),
                    i.isChecked(),
                    i.getCheckedAt()
            );
        }
    }

    /**
     * @param aliasByProduct          the household's explicit product renames
     *                                (household_product_aliases), keyed by product id.
     * @param productFriendlyByProduct the household's friendly name per product from its
     *                                confirmed receipts, keyed by product id — the
     *                                displayName fallback when no explicit alias exists.
     *                                See {@code ShoppingListService}.
     */
    public static ShoppingListResponse from(ShoppingList list,
                                            Map<UUID, String> aliasByProduct,
                                            Map<UUID, String> productFriendlyByProduct) {
        var items = list.getItems().stream()
                .map(i -> {
                    var productId = i.getProduct() != null ? i.getProduct().getId() : null;
                    return Item.from(i,
                            productId != null ? aliasByProduct.get(productId) : null,
                            productId != null ? productFriendlyByProduct.get(productId) : null);
                })
                .toList();
        var checked = (int) items.stream().filter(Item::checked).count();
        return new ShoppingListResponse(
                list.getId(),
                list.getName(),
                list.getCreatedBy().getId(),
                list.getCreatedAt(),
                list.getUpdatedAt(),
                items.size(),
                checked,
                items
        );
    }
}
