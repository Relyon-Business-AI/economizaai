package com.relyon.economizaai.service.geo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistanceCalculatorTest {

    @Test
    void zeroDistanceForSamePoint() {
        var d = DistanceCalculator.kmBetween(
                new BigDecimal("-30.0277"), new BigDecimal("-51.2287"),
                new BigDecimal("-30.0277"), new BigDecimal("-51.2287"));
        assertEquals(0.0, d, 0.001);
    }

    @Test
    void portoAlegreToHipica_isAround12Km() {
        // Centro de Porto Alegre → Bairro Hípica (Zaffari Hípica)
        var d = DistanceCalculator.kmBetween(
                new BigDecimal("-30.0277"), new BigDecimal("-51.2287"),
                new BigDecimal("-30.1314"), new BigDecimal("-51.2018"));
        assertTrue(d > 10 && d < 15, "expected ~12 km, got " + d);
    }

    @Test
    void infinityWhenAnyCoordinateIsNull() {
        assertEquals(Double.POSITIVE_INFINITY, DistanceCalculator.kmBetween(
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals(Double.POSITIVE_INFINITY, DistanceCalculator.kmBetween(
                BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO));
    }
}
