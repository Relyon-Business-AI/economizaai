package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UnitNormalizerTest {

    @Test
    void stripsErpTaxGroupSuffixDigits() {
        // WMS/Sam's Club prints uCom as unit + internal digit.
        assertEquals("UN", UnitNormalizer.normalize("UND9"));
        assertEquals("UN", UnitNormalizer.normalize("UND8"));
        assertEquals("KG", UnitNormalizer.normalize("KG9"));
    }

    @Test
    void aliasesUnitSpellingsToUn() {
        assertEquals("UN", UnitNormalizer.normalize("UND"));
        assertEquals("UN", UnitNormalizer.normalize("UNID"));
        assertEquals("UN", UnitNormalizer.normalize("uni"));
    }

    @Test
    void keepsCleanUnitsUnchanged() {
        assertEquals("UN", UnitNormalizer.normalize("UN"));
        assertEquals("KG", UnitNormalizer.normalize("kg"));
        assertEquals("PT", UnitNormalizer.normalize("PT"));
        assertEquals("L", UnitNormalizer.normalize("L"));
    }

    @Test
    void keepsDimensionedUnitsIntact() {
        assertEquals("M2", UnitNormalizer.normalize("M2"));
        assertEquals("M3", UnitNormalizer.normalize("m3"));
    }

    @Test
    void nullOrBlankBecomesNull() {
        assertNull(UnitNormalizer.normalize(null));
        assertNull(UnitNormalizer.normalize("  "));
    }
}
