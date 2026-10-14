package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.service.extraction.ProductNameLeafClassifier.Confidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductNameLeafClassifierTest {

    @Test
    void highConfidence_singleGroupStrongKeyword() {
        var r = ProductNameLeafClassifier.classify("QUEIJO MUSSARELA FATIADO");
        assertEquals("queijos", r.slug());
        assertEquals(Confidence.HIGH, r.confidence());

        assertEquals("carnes-bovinas", ProductNameLeafClassifier.classify("PICANHA BOVINA RESFRIADA").slug());
        assertEquals(Confidence.HIGH, ProductNameLeafClassifier.classify("CERVEJA HEINEKEN 330ML").confidence());
        assertEquals("graos-massas", ProductNameLeafClassifier.classify("ARROZ TIO JOAO 5KG").slug());
    }

    @Test
    void lowConfidence_conflictAcrossGroups() {
        // noodle + meat flavor, and juice + fruit — ambiguous → route to LLM
        assertEquals(Confidence.LOW, ProductNameLeafClassifier.classify("MACARRAO NISSIN PICANHA 80G").confidence());
        assertEquals(Confidence.LOW, ProductNameLeafClassifier.classify("SUCO DE LIMAO NATURAL 1L").confidence());
    }

    @Test
    void lowConfidence_weakKeywordOnly() {
        // "leite" alone is weak (leite de coco isn't dairy) → LOW
        assertEquals(Confidence.LOW, ProductNameLeafClassifier.classify("LEITE DE COCO 200ML").confidence());
    }

    @Test
    void none_whenNoKeyword() {
        assertEquals(Confidence.NONE, ProductNameLeafClassifier.classify("PRODUTO GENERICO XYZ 99").confidence());
        assertEquals(Confidence.NONE, ProductNameLeafClassifier.classify("").confidence());
    }

    @Test
    void wordBoundary_noSubstringFalseHits() {
        assertEquals("graos-massas", ProductNameLeafClassifier.classify("MACARRAO MIOJO LAMEN 85G").slug());
        assertEquals("legumes-verduras", ProductNameLeafClassifier.classify("TOMATE LONGA VIDA KG").slug());
        assertEquals("frutas", ProductNameLeafClassifier.classify("MACA FUJI KG").slug());
    }
}
