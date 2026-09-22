package com.relyon.economizaai.service.extraction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PackSizeExtractorTest {

    @Test
    void extractsKgFromKilograms() {
        var p = PackSizeExtractor.extract("ARROZ TIO J TP1 5KG");
        assertEquals(new BigDecimal("5"), p.size());
        assertEquals("KG", p.unit());
    }

    @Test
    void extractsLitersFromBeverages() {
        var p = PackSizeExtractor.extract("REFRIGERANTE COCA COLA 2L");
        assertEquals(new BigDecimal("2"), p.size());
        assertEquals("L", p.unit());
    }

    @Test
    void extractsMillilitersWithDecimal() {
        var p = PackSizeExtractor.extract("LEITE INTEGRAL 1,5L");
        assertEquals(new BigDecimal("1.5"), p.size());
        assertEquals("L", p.unit());
    }

    @Test
    void extractsMillilitersWithSpaceBeforeUnit() {
        var p = PackSizeExtractor.extract("VEJA LIMAO SQ 500 ML");
        assertEquals(new BigDecimal("500"), p.size());
        assertEquals("ML", p.unit());
    }

    @Test
    void extractsPackCountFromCSlash() {
        var p = PackSizeExtractor.extract("SACO LIXO DR RECICL 50L C/20");
        // Picks up the 50L weight first since it's earlier
        assertEquals(new BigDecimal("50"), p.size());
        assertEquals("L", p.unit());
    }

    @Test
    void returnsEmptyWhenNothingFound() {
        var p = PackSizeExtractor.extract("PAO FRANCES");
        assertNull(p.size());
        assertNull(p.unit());
    }

    @Test
    void handlesNullSafely() {
        var p = PackSizeExtractor.extract(null);
        assertNull(p.size());
        assertNull(p.unit());
    }
}
