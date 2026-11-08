package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the discount contract on the FE-facing receipt responses: the
 * receipt-level discount is carried through as {@code discountTotal}, and
 * because item prices are kept gross (no distribution), {@code
 * householdTotalAmount} (sum of items) can exceed {@code totalAmount}
 * (what was paid) by exactly the discount.
 */
class ReceiptResponseDiscountMappingTest {

    private Receipt receiptWithDiscount(BigDecimal discountTotal) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .totalAmount(new BigDecimal("76.00"))
                .discountTotal(discountTotal)
                .status(ReceiptStatus.CONFIRMED)
                .build();
        receipt.addItem(ReceiptItem.builder().totalPrice(new BigDecimal("50.00")).build());
        receipt.addItem(ReceiptItem.builder().totalPrice(new BigDecimal("30.00")).build());
        return receipt;
    }

    @Test
    void receiptResponse_carriesDiscountAndKeepsGrossHouseholdTotal() {
        var response = ReceiptResponse.from(receiptWithDiscount(new BigDecimal("4.00")));

        assertEquals(0, response.discountTotal().compareTo(new BigDecimal("4.00")));
        // gross item sum, NOT netted to what was paid
        assertEquals(0, response.householdTotalAmount().compareTo(new BigDecimal("80.00")));
        assertEquals(0, response.totalAmount().compareTo(new BigDecimal("76.00")));
        // documented invariant: sum(items) == paid + discount
        assertEquals(0, response.householdTotalAmount()
                .compareTo(response.totalAmount().add(response.discountTotal())));
    }

    @Test
    void receiptSummaryResponse_carriesDiscountAndKeepsGrossHouseholdTotal() {
        var summary = ReceiptSummaryResponse.from(receiptWithDiscount(new BigDecimal("4.00")));

        assertEquals(0, summary.discountTotal().compareTo(new BigDecimal("4.00")));
        assertEquals(0, summary.householdTotalAmount().compareTo(new BigDecimal("80.00")));
        assertEquals(0, summary.totalAmount().compareTo(new BigDecimal("76.00")));
    }

    @Test
    void nullPricedItem_doesNotNpeAndSumsOnlyPricedItems() {
        // Nota de FOTO: uma linha SEM preço (IA não leu) + duas com preço. A soma do total do
        // domicílio não pode dar NPE — antes BigDecimal.add(null) derrubava listagem/detalhe (500).
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .totalAmount(new BigDecimal("80.00"))
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .build();
        receipt.addItem(ReceiptItem.builder().totalPrice(new BigDecimal("50.00")).build());
        receipt.addItem(ReceiptItem.builder().totalPrice(null).build()); // linha sem preço
        receipt.addItem(ReceiptItem.builder().totalPrice(new BigDecimal("30.00")).build());

        assertEquals(0, ReceiptResponse.from(receipt).householdTotalAmount().compareTo(new BigDecimal("80.00")));
        assertEquals(0, ReceiptSummaryResponse.from(receipt).householdTotalAmount().compareTo(new BigDecimal("80.00")));
    }

    @Test
    void discountTotal_isNullWhenReceiptHadNoDiscount() {
        assertNull(ReceiptResponse.from(receiptWithDiscount(null)).discountTotal());
        assertNull(ReceiptSummaryResponse.from(receiptWithDiscount(null)).discountTotal());
    }
}
