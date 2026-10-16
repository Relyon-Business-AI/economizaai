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
    void utilidades_householdNonFood() {
        // itens que antes caíam em "não-mercado" agora batem em Utilidades/Descartáveis deterministicamente
        assertEquals("utilidades", ProductNameSubcategoryClassifier.classify("PILHA DURACELL AA C/4").slug());
        assertEquals(Confidence.HIGH, ProductNameSubcategoryClassifier.classify("PILHA DURACELL AA C/4").confidence());
        assertEquals("utilidades", ProductNameSubcategoryClassifier.classify("LAMP LED PHILIPS 1521LM 16W").slug());
        assertEquals("utilidades", ProductNameSubcategoryClassifier.classify("FOSFORO FIAT LUX EXTRA LONGO 50P").slug());
        assertEquals("utilidades", ProductNameSubcategoryClassifier.classify("CARVAO ACACIA NEGRA 5KG").slug());
        assertEquals("descartaveis", ProductNameSubcategoryClassifier.classify("LUVAS VINIL G C/100").slug());
    }

    @Test
    void ambiguousPureCase_nowHigh() {
        // casos puros que antes caíam no LLM (fraco) agora são determinísticos
        assertEquals("frutas", ProductNameSubcategoryClassifier.classify("LIMAO TAHITI KG").slug());
        assertEquals(Confidence.HIGH, ProductNameSubcategoryClassifier.classify("LIMAO TAHITI KG").confidence());
        assertEquals("agua", ProductNameSubcategoryClassifier.classify("AGUA CRYSTAL 1,5L").slug());
        assertEquals(Confidence.HIGH, ProductNameSubcategoryClassifier.classify("AGUA CRYSTAL 1,5L").confidence());
        assertEquals("cafe-cha", ProductNameSubcategoryClassifier.classify("CHA GELADO LIPTON LIMAO 1,5L").slug());
    }

    @Test
    void contextExclusions_disqualifyTheFruitWaterRules() {
        // "suco de limão" é suco, não fruta
        assertEquals("sucos", ProductNameSubcategoryClassifier.classify("SUCO DE LIMAO DEL VALLE 1L").slug());
        // "água sanitária" NÃO é água (é limpeza → sem folha aqui = NONE)
        assertEquals(Confidence.NONE, ProductNameSubcategoryClassifier.classify("AGUA SANITARIA YPE 2L").confidence());
        // "detergente limão" não é fruta
        assertEquals(Confidence.NONE, ProductNameSubcategoryClassifier.classify("DETERGENTE LIMAO YPE 500ML").confidence());
    }

    @Test
    void outrosPerCategory_catchAll() {
        assertEquals("bebidas-alcoolicas-outros", ProductNameSubcategoryClassifier.classify("BEBIDA ALCOOLICA MISTA ICE 269ML").slug());
        assertEquals(Confidence.HIGH, ProductNameSubcategoryClassifier.classify("BEBIDA ALCOOLICA MISTA ICE 269ML").confidence());
        assertEquals("bebidas-outros", ProductNameSubcategoryClassifier.classify("ENERGETICO RED BULL 250ML").slug());
        assertEquals("bebidas-outros", ProductNameSubcategoryClassifier.classify("ISOTONICO GATORADE 500ML").slug());
    }

    @Test
    void cola_doesNotHijackCocaCola() {
        // "cola" sozinho casaria "coca cola"; por isso usamos "cola bastao"/"cola branca" etc.
        assertEquals("refrigerantes", ProductNameSubcategoryClassifier.classify("COCA COLA 2L").slug());
        assertEquals(Confidence.HIGH, ProductNameSubcategoryClassifier.classify("COCA COLA 2L").confidence());
    }

    @Test
    void lowConfidence_conflictAcrossGroups() {
        // noodle + meat flavor — ambíguo → vai pro LLM
        assertEquals(Confidence.LOW, ProductNameSubcategoryClassifier.classify("MACARRAO NISSIN PICANHA 80G").confidence());
        // ("suco de limão" não é mais conflito — a exclusão resolve pra sucos; ver contextExclusions)
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
