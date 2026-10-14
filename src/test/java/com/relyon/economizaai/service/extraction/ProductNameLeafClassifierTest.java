package com.relyon.economizaai.service.extraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductNameLeafClassifierTest {

    @Test
    void splitsDairyFromMeat() {
        assertEquals("iogurtes", ProductNameLeafClassifier.classify("IOGURTE NATURAL DANONE 170G"));
        assertEquals("queijos", ProductNameLeafClassifier.classify("QUEIJO MUSSARELA FATIADO"));
        assertEquals("leite", ProductNameLeafClassifier.classify("LEITE INTEGRAL ITAMBE 1L"));
        assertEquals("outros-laticinios", ProductNameLeafClassifier.classify("CREME DE LEITE NESTLE 200G"));
        assertEquals("aves", ProductNameLeafClassifier.classify("PEITO DE FRANGO SADIA KG"));
        assertEquals("carnes-bovinas", ProductNameLeafClassifier.classify("PICANHA BOVINA RESFRIADA"));
    }

    @Test
    void splitsAlcoholFromBeverages() {
        assertEquals("cervejas", ProductNameLeafClassifier.classify("CERVEJA HEINEKEN LONG NECK 330ML"));
        assertEquals("vinhos", ProductNameLeafClassifier.classify("VINHO TINTO SECO"));
        assertEquals("refrigerantes", ProductNameLeafClassifier.classify("REFRIGERANTE COCA COLA 2L"));
        assertEquals("cafe-cha", ProductNameLeafClassifier.classify("CAFE TORRADO E MOIDO PILAO 500G"));
    }

    @Test
    void producePantryAndNull() {
        assertEquals("frutas", ProductNameLeafClassifier.classify("BANANA PRATA KG"));
        assertEquals("graos-massas", ProductNameLeafClassifier.classify("ARROZ BRANCO TIO JOAO 5KG"));
        assertEquals("chocolates", ProductNameLeafClassifier.classify("CHOCOLATE LACTA AO LEITE"));
        assertNull(ProductNameLeafClassifier.classify("PRODUTO GENERICO XYZ"));
        assertNull(ProductNameLeafClassifier.classify(""));
    }

    @Test
    void wordBoundary_shortKeywordsDoNotMatchSubstrings() {
        // "maca" (maçã) must not match "macarrao"; "mate" (chá) not "tomate".
        assertEquals("graos-massas", ProductNameLeafClassifier.classify("MACARRAO NISSIN MIOJO 85G"));
        // instant-noodle flavor names must not win (picanha/tomate) — noodle rule is higher priority
        assertEquals("graos-massas", ProductNameLeafClassifier.classify("MACARRAO NISSIN PICANHA 80G"));
        assertEquals("frutas", ProductNameLeafClassifier.classify("MACA FUJI KG"));
        assertEquals("legumes-verduras", ProductNameLeafClassifier.classify("TOMATE LONGA VIDA KG"));
        // "original" removed as a beer keyword (too generic); the brand still wins.
        assertEquals("cervejas", ProductNameLeafClassifier.classify("CERVEJA ANTARCTICA ORIGINAL 600ML"));
    }
}
