package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the review-preview contract for the AI category suggestion: an item not yet
 * linked to a product carries the AI-suggested subcategory (slug + localized label)
 * and is flagged {@code categorySuggested}; linked items keep the PRODUCT's node.
 */
class ReceiptResponseAiSuggestionTest {

    private Receipt pendingReceipt(ReceiptItem... items) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .totalAmount(new BigDecimal("10.00"))
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .build();
        for (var item : items) receipt.addItem(item);
        return receipt;
    }

    private ReceiptItem item() {
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .rawDescription("TORRONE FLORESTA 50G")
                .totalPrice(new BigDecimal("10.00"))
                .build();
    }

    @Test
    void unlinkedItem_carriesAiSuggestedNode_andIsFlaggedSuggested() {
        var receiptItem = item();
        var receipt = pendingReceipt(receiptItem);

        var response = ReceiptResponse.from(receipt, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(receiptItem.getId(), "doces"), Map.of(receiptItem.getId(), "Doces"));

        var itemResponse = response.items().get(0);
        assertEquals("doces", itemResponse.categoryNodeSlug());
        assertEquals("Doces", itemResponse.categoryNodeLabel());
        assertTrue(itemResponse.categorySuggested());
    }

    @Test
    void unlinkedItem_showsBothSuggestedCategoryAndAiNode() {
        // caso real da revisão: o determinístico deu a CATEGORIA (preview) e a IA deu a SUBCATEGORIA.
        // A categoria sugerida (overrideLabel) NÃO pode suprimir o node sugerido.
        var receiptItem = item();
        var receipt = pendingReceipt(receiptItem);

        var response = ReceiptResponse.from(receipt, Map.of(),
                Map.of(receiptItem.getId(), "GROCERIES"),          // categoria sugerida (preview determinístico)
                Map.of(), Map.of(),
                Map.of(receiptItem.getId(), "mercearia-outros"),   // subcategoria sugerida pela IA
                Map.of(receiptItem.getId(), "Outros da mercearia"));

        var itemResponse = response.items().get(0);
        assertEquals("GROCERIES", itemResponse.category());
        assertEquals("mercearia-outros", itemResponse.categoryNodeSlug());
        assertEquals("Outros da mercearia", itemResponse.categoryNodeLabel());
        assertTrue(itemResponse.categorySuggested());
    }

    @Test
    void linkedItem_usesProductNodeNotAiSuggestion() {
        var receiptItem = item();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Torrone").build();
        receiptItem.setProduct(product);
        var receipt = pendingReceipt(receiptItem);

        var response = ReceiptResponse.from(receipt, Map.of(), Map.of(),
                Map.of(product.getId(), "doces"), Map.of(product.getId(), "Doces"),
                Map.of(receiptItem.getId(), "frutas"), Map.of(receiptItem.getId(), "Frutas"));

        var itemResponse = response.items().get(0);
        assertEquals("doces", itemResponse.categoryNodeSlug()); // nó do PRODUTO, não a sugestão
        assertFalse(itemResponse.categorySuggested());
    }

    @Test
    void unlinkedItem_withoutAnySuggestion_isNotFlagged() {
        var receipt = pendingReceipt(item());

        var response = ReceiptResponse.from(receipt, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

        var itemResponse = response.items().get(0);
        assertNull(itemResponse.categoryNodeSlug());
        assertFalse(itemResponse.categorySuggested());
    }
}
