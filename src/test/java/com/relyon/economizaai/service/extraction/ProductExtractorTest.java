package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.ProductCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductExtractorTest {

    private ProductExtractor extractor;

    @BeforeEach
    void setUp() {
        var brandExtractor = SeedFixtures.loadedBrandExtractor();
        var dictionaryClassifier = SeedFixtures.loadedDictionaryClassifier();
        extractor = new ProductExtractor(brandExtractor, dictionaryClassifier);
    }

    @Test
    void extractsArrozTioJoao5kg() {
        var e = extractor.extract("ARROZ TIO J TP1 5KG");
        assertEquals("Arroz", e.genericName());
        assertEquals("Tio João", e.brand());
        assertEquals(new BigDecimal("5"), e.packSize());
        assertEquals("KG", e.packUnit());
        assertEquals(ProductCategory.GROCERIES, e.category());
    }

    @Test
    void extractsLimpadorVeja500ml() {
        var e = extractor.extract("LIMP COZ VEJA LIMAO SQ500ML PROM");
        assertEquals("Limpador", e.genericName());
        assertEquals("Veja", e.brand());
        assertEquals(new BigDecimal("500"), e.packSize());
        assertEquals("ML", e.packUnit());
        assertEquals(ProductCategory.CLEANING, e.category());
    }

    @Test
    void extractsLeiteItambe1L() {
        var e = extractor.extract("LEITE INTEGRAL ITAMBE 1L");
        assertEquals("Leite", e.genericName());
        assertEquals("Itambé", e.brand());
        assertEquals(new BigDecimal("1"), e.packSize());
        assertEquals("L", e.packUnit());
        assertEquals(ProductCategory.MEAT_DAIRY, e.category());
    }

    @Test
    void unknownItemReturnsAllNulls() {
        var e = extractor.extract("PRODUTO XYZ DESCONHECIDO");
        assertNull(e.genericName());
        assertNull(e.brand());
        assertNull(e.packSize());
        assertNull(e.packUnit());
        assertNull(e.category());
    }
}
