package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.service.priceindex.UnitConverter.BaseUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitConverterTest {

    @Test
    void normalizesItemSoldInBaseUnit() {
        // 1.5 kg of arroz at R$15 → R$10/kg
        var result = UnitConverter.normalizeItemPrice(
                new BigDecimal("1.5"), "kg", null, null, new BigDecimal("15.00"));
        assertTrue(result.isPresent());
        assertEquals(BaseUnit.KG, result.get().baseUnit());
        assertEquals(0, new BigDecimal("10.0000").compareTo(result.get().pricePerBaseUnit()));
    }

    @Test
    void normalizesItemSoldByGramsToKg() {
        // 500 g at R$5 → R$10/kg
        var result = UnitConverter.normalizeItemPrice(
                new BigDecimal("500"), "g", null, null, new BigDecimal("5.00"));
        assertTrue(result.isPresent());
        assertEquals(BaseUnit.KG, result.get().baseUnit());
        assertEquals(0, new BigDecimal("10.0000").compareTo(result.get().pricePerBaseUnit()));
    }

    @Test
    void normalizesItemSoldInMlToL() {
        // 1000 ml at R$8 → R$8/L
        var result = UnitConverter.normalizeItemPrice(
                new BigDecimal("1000"), "ml", null, null, new BigDecimal("8.00"));
        assertTrue(result.isPresent());
        assertEquals(BaseUnit.L, result.get().baseUnit());
        assertEquals(0, new BigDecimal("8.0000").compareTo(result.get().pricePerBaseUnit()));
    }

    @Test
    void usesPackSizeWhenItemIsSoldByUnit() {
        // 2 units (UN) of milk-1L at R$10 each → R$5/L
        var result = UnitConverter.normalizeItemPrice(
                new BigDecimal("2"), "UN", new BigDecimal("1"), "L", new BigDecimal("10.00"));
        assertTrue(result.isPresent());
        assertEquals(BaseUnit.L, result.get().baseUnit());
        assertEquals(0, new BigDecimal("5.0000").compareTo(result.get().pricePerBaseUnit()));
    }

    @Test
    void packSizeNormalizationHandlesGrams() {
        // 1 box (CX) of biscoito-200g at R$3 → R$15/kg
        var result = UnitConverter.normalizeItemPrice(
                new BigDecimal("1"), "CX", new BigDecimal("200"), "g", new BigDecimal("3.00"));
        assertTrue(result.isPresent());
        assertEquals(BaseUnit.KG, result.get().baseUnit());
        assertEquals(0, new BigDecimal("15.0000").compareTo(result.get().pricePerBaseUnit()));
    }

    @Test
    void emptyWhenItemUnitUnknownAndNoPackInfo() {
        var result = UnitConverter.normalizeItemPrice(
                new BigDecimal("1"), "FANTASY_UNIT", null, null, new BigDecimal("5.00"));
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyWhenQuantityZero() {
        var result = UnitConverter.normalizeItemPrice(
                BigDecimal.ZERO, "kg", null, null, new BigDecimal("5.00"));
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyWhenItemUnitIsCountAndNoPackInfo() {
        var result = UnitConverter.normalizeItemPrice(
                new BigDecimal("1"), "UN", null, null, new BigDecimal("5.00"));
        assertTrue(result.isEmpty());
    }

    @Test
    void treatsLowercaseAndUppercaseAsSame() {
        var lower = UnitConverter.normalizeItemPrice(
                new BigDecimal("1"), "kg", null, null, new BigDecimal("10"));
        var upper = UnitConverter.normalizeItemPrice(
                new BigDecimal("1"), "KG", null, null, new BigDecimal("10"));
        assertEquals(lower, upper);
    }
}
