package com.relyon.economizaai.service.notifications;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelevanceThresholdTest {

    @Test
    void cheapItemsRequireTheMaxDrop() {
        assertEquals(0.20, RelevanceThreshold.requiredDropFraction(new BigDecimal("1.00")), 1e-9);
        assertEquals(0.20, RelevanceThreshold.requiredDropFraction(new BigDecimal("0.50")), 1e-9);
    }

    @Test
    void priceyItemsRequireTheMinDrop() {
        assertEquals(0.05, RelevanceThreshold.requiredDropFraction(new BigDecimal("200.00")), 1e-9);
        assertEquals(0.05, RelevanceThreshold.requiredDropFraction(new BigDecimal("500.00")), 1e-9);
    }

    @Test
    void midPricesInterpolateMonotonicallyBetweenAnchors() {
        var cheap = RelevanceThreshold.requiredDropFraction(new BigDecimal("5.00"));
        var mid = RelevanceThreshold.requiredDropFraction(new BigDecimal("50.00"));
        assertTrue(cheap > mid);
        assertTrue(cheap < 0.20);
        assertTrue(mid > 0.05);
    }
}
