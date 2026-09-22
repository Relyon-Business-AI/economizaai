package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.ReceiptItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptItemResponseTest {

    @Test
    void mapsPaidPriceAndFlagsPromotionalWhenPaidBelowOriginal() {
        var item = ReceiptItem.builder()
                .lineNumber(1)
                .rawDescription("ARROZ TESTE 5KG")
                .quantity(new BigDecimal("1"))
                .unit("UN")
                .unitPrice(new BigDecimal("25.00"))
                .totalPrice(new BigDecimal("25.00"))
                .paidUnitPrice(new BigDecimal("19.90"))
                .paidTotalPrice(new BigDecimal("19.90"))
                .build();

        var response = ReceiptItemResponse.from(item);

        assertEquals(0, response.paidTotalPrice().compareTo(new BigDecimal("19.90")));
        assertEquals(0, response.paidUnitPrice().compareTo(new BigDecimal("19.90")));
        assertTrue(response.promotional(), "paid 19.90 < 25.00 → promotional");
    }

    @Test
    void notPromotionalWhenNoPaidPrice() {
        var item = ReceiptItem.builder()
                .lineNumber(2)
                .rawDescription("FEIJAO TESTE 1KG")
                .quantity(new BigDecimal("1"))
                .unit("UN")
                .unitPrice(new BigDecimal("8.00"))
                .totalPrice(new BigDecimal("8.00"))
                .build();

        var response = ReceiptItemResponse.from(item);

        assertNull(response.paidTotalPrice());
        assertNull(response.paidUnitPrice());
        assertFalse(response.promotional());
    }
}
