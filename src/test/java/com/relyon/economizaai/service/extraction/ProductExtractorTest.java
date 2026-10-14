package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.CategoryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductExtractorTest {

    private ProductExtractor extractor;
    private CategoryResolver categoryResolver;

    @BeforeEach
    void setUp() {
        var brandExtractor = SeedFixtures.loadedBrandExtractor();
        var dictionaryClassifier = SeedFixtures.loadedDictionaryClassifier();
        categoryResolver = mock(CategoryResolver.class);
        extractor = new ProductExtractor(brandExtractor, dictionaryClassifier, categoryResolver);
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

    @Test
    void keywordFallbackFillsFineNode_onHighConfidenceWhenNoDictNode() {
        var queijosNode = UUID.randomUUID();
        when(categoryResolver.idForSlug("queijos")).thenReturn(queijosNode);
        // seed dictionary carries no category_id → the HIGH keyword match fills the fine node.
        var e = extractor.extract("QUEIJO MUSSARELA TIROLEZ 500G");
        assertEquals(queijosNode, e.categoryId());
    }

    @Test
    void keywordFallbackLeavesNodeNull_whenNoHighMatch() {
        var e = extractor.extract("PRODUTO XYZ DESCONHECIDO");
        assertNull(e.categoryId());
        verify(categoryResolver, never()).idForSlug(any());
    }

    @Test
    void keywordFallbackSkipped_whenDictionaryAlreadyGaveNode() {
        var dictNode = UUID.randomUUID();
        var dictionaryWithNode = mock(DictionaryClassifier.class);
        when(dictionaryWithNode.classify(any())).thenReturn(new DictionaryClassifier.DictEntry(
                "Queijo", null, ProductCategory.MEAT_DAIRY, dictNode, CategorizationSource.DICTIONARY));
        var extractorWithNode = new ProductExtractor(
                SeedFixtures.loadedBrandExtractor(), dictionaryWithNode, categoryResolver);

        var e = extractorWithNode.extract("QUEIJO MUSSARELA 500G");

        assertEquals(dictNode, e.categoryId());
        verify(categoryResolver, never()).idForSlug(any()); // nó do dicionário não é sobrescrito
    }
}
