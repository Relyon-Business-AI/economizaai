package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.service.extraction.ProductNameSubcategoryClassifier.Confidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductNameSubcategoryClassifierTest {

    @Test
    void highConfidence_singleGroupStrongKeyword() {
        var r = ProductNameSubcategoryClassifier.classify("QUEIJO MUSSARELA FATIADO");
        assertEquals("queijos", r.slug());
        assertEquals(Confidence.HIGH, r.confidence());

        assertEquals("carnes-bovinas", ProductNameSubcategoryClassifier.classify("PICANHA BOVINA RESFRIADA").slug());
        assertEquals(Confidence.HIGH, ProductNameSubcategoryClassifier.classify("CERVEJA HEINEKEN 330ML").confidence());
        assertEquals("graos-massas", ProductNameSubcategoryClassifier.classify("ARROZ TIO JOAO 5KG").slug());
    }

    @Test
    void lowConfidence_conflictAcrossGroups() {
        // noodle + meat flavor, and juice + fruit — ambiguous → route to LLM
        assertEquals(Confidence.LOW, ProductNameSubcategoryClassifier.classify("MACARRAO NISSIN PICANHA 80G").confidence());
        assertEquals(Confidence.LOW, ProductNameSubcategoryClassifier.classify("SUCO DE LIMAO NATURAL 1L").confidence());
    }

    @Test
    void lowConfidence_weakKeywordOnly() {
        // "leite" alone is weak (leite de coco isn't dairy) → LOW
        assertEquals(Confidence.LOW, ProductNameSubcategoryClassifier.classify("LEITE DE COCO 200ML").confidence());
    }

    @Test
    void none_whenNoKeyword() {
        assertEquals(Confidence.NONE, ProductNameSubcategoryClassifier.classify("PRODUTO GENERICO XYZ 99").confidence());
        assertEquals(Confidence.NONE, ProductNameSubcategoryClassifier.classify("").confidence());
    }

    @Test
    void wordBoundary_noSubstringFalseHits() {
        assertEquals("graos-massas", ProductNameSubcategoryClassifier.classify("MACARRAO MIOJO LAMEN 85G").slug());
        assertEquals("legumes-verduras", ProductNameSubcategoryClassifier.classify("TOMATE LONGA VIDA KG").slug());
        assertEquals("frutas", ProductNameSubcategoryClassifier.classify("MACA FUJI KG").slug());
    }
}
