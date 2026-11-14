package com.relyon.economizaai.service.geo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HomeLocationPrecisionTest {

    @Test
    void coarsen_keepsThreeDecimalPlaces() {
        assertEquals(new BigDecimal("-30.028"), HomeLocationPrecision.coarsen(new BigDecimal("-30.0277431")));
        assertEquals(new BigDecimal("-51.229"), HomeLocationPrecision.coarsen(new BigDecimal("-51.2287000")));
    }

    @Test
    void coarsen_padsShorterValues() {
        assertEquals(new BigDecimal("-30.000"), HomeLocationPrecision.coarsen(new BigDecimal("-30")));
    }

    @Test
    void coarsen_keepsNull() {
        assertNull(HomeLocationPrecision.coarsen(null));
    }
}
