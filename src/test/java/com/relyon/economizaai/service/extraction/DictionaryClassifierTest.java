package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.ProductCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DictionaryClassifierTest {

    private final DictionaryClassifier classifier = SeedFixtures.loadedDictionaryClassifier();

    @Test
    void classifiesArrozAsGroceries() {
        var entry = classifier.classify("ARROZ TIO J TP1 5KG");
        assertEquals("Arroz", entry.genericName());
        assertEquals(ProductCategory.GROCERIES, entry.category());
    }

    @Test
    void classifiesLeiteAsMeatDairy() {
        var entry = classifier.classify("LEITE INTEGRAL ITAMBE 1L");
        assertEquals("Leite", entry.genericName());
        assertEquals(ProductCategory.MEAT_DAIRY, entry.category());
    }

    @Test
    void classifiesBrandOnlyVejaAsCleaning() {
        var entry = classifier.classify("VEJA");
        assertNull(entry.genericName());
        assertEquals(ProductCategory.CLEANING, entry.category());
    }

    @Test
    void prefersTwoWordKeywordOverSingleWord() {
        var entry = classifier.classify("SACO LIXO 50L C/20");
        assertEquals("Saco de Lixo", entry.genericName());
        assertEquals(ProductCategory.CLEANING, entry.category());
    }

    @Test
    void returnsEmptyForUnknown() {
        var entry = classifier.classify("PRODUTO ALIENIGENA XYZ");
        assertNull(entry.genericName());
        assertNull(entry.category());
    }
}
