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
    void keywordRefineSkipped_whenDictionaryGaveLeafNode() {
        var dictLeaf = UUID.randomUUID();
        when(categoryResolver.isLeafNode(dictLeaf)).thenReturn(true);
        var extractorWithLeaf = extractorWithDictNode(dictLeaf);

        var e = extractorWithLeaf.extract("QUEIJO MUSSARELA 500G");

        assertEquals(dictLeaf, e.categoryId(), "an already-fine leaf is not touched");
        verify(categoryResolver, never()).idForSlug(any());
    }

    @Test
    void keywordRefinesGroupNode_toLeafWithinSameGroup() {
        var dictGroup = UUID.randomUUID();
        var queijosLeaf = UUID.randomUUID();
        when(categoryResolver.isLeafNode(dictGroup)).thenReturn(false);
        when(categoryResolver.idForSlug("queijos")).thenReturn(queijosLeaf);
        when(categoryResolver.groupIdOf(dictGroup)).thenReturn(dictGroup);
        when(categoryResolver.groupIdOf(queijosLeaf)).thenReturn(dictGroup);
        var extractorWithGroup = extractorWithDictNode(dictGroup);

        var e = extractorWithGroup.extract("QUEIJO MUSSARELA 500G");

        assertEquals(queijosLeaf, e.categoryId(), "a group node is refined to the keyword leaf under it");
    }

    @Test
    void keywordRefineRejected_whenLeafBelongsToAnotherGroup() {
        var dictGroup = UUID.randomUUID();
        var leafFromOtherGroup = UUID.randomUUID();
        when(categoryResolver.isLeafNode(dictGroup)).thenReturn(false);
        when(categoryResolver.idForSlug("queijos")).thenReturn(leafFromOtherGroup);
        when(categoryResolver.groupIdOf(dictGroup)).thenReturn(dictGroup);
        when(categoryResolver.groupIdOf(leafFromOtherGroup)).thenReturn(UUID.randomUUID());
        var extractorWithGroup = extractorWithDictNode(dictGroup);

        var e = extractorWithGroup.extract("QUEIJO MUSSARELA 500G");

        assertEquals(dictGroup, e.categoryId(), "a cross-group keyword leaf never overrides the dictionary group");
    }

    private ProductExtractor extractorWithDictNode(UUID node) {
        var dictionaryWithNode = mock(DictionaryClassifier.class);
        when(dictionaryWithNode.classify(any())).thenReturn(new DictionaryClassifier.DictEntry(
                "Queijo", null, ProductCategory.MEAT_DAIRY, node, CategorizationSource.DICTIONARY));
        return new ProductExtractor(SeedFixtures.loadedBrandExtractor(), dictionaryWithNode, categoryResolver);
    }
}
