package com.relyon.economizaai.service.priceindex;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PriceIndexServiceHelpersTest {

    @Test
    void medianOfOddCount() {
        var m = PriceIndexService.median(List.of(
                new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("20")));
        assertEquals(0, m.compareTo(new BigDecimal("10")));
    }

    @Test
    void medianOfEvenCountAveragesMiddle() {
        var m = PriceIndexService.median(List.of(
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("40")));
        assertEquals(0, m.compareTo(new BigDecimal("25")));
    }

    @Test
    void medianOfEmptyIsNull() {
        assertNull(PriceIndexService.median(List.of()));
        assertNull(PriceIndexService.median(null));
    }

    @Test
    void cnpjRootTakesFirst8Digits() {
        assertEquals("93015006", PriceIndexService.cnpjRoot("93015006005182"));
        assertEquals("12345678", PriceIndexService.cnpjRoot("12345678000190"));
    }

    @Test
    void cnpjRootHandlesShortInputGracefully() {
        assertEquals("123", PriceIndexService.cnpjRoot("123"));
        assertNull(PriceIndexService.cnpjRoot(null));
    }
}
