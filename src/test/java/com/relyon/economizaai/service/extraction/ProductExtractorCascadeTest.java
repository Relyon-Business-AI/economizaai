package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.extraction.DictionaryClassifier.DictEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the cascade composition in {@link ProductExtractor} with fully-mocked
 * collaborators, complementing the fixture-backed {@code ProductExtractorTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProductExtractorCascadeTest {

    @Mock
    private BrandExtractor brandExtractor;

    @Mock
    private DictionaryClassifier dictionaryClassifier;

    @InjectMocks
    private ProductExtractor extractor;

    @Test
    void blankDescriptionReturnsEmptyWithoutTouchingCollaborators() {
        var result = extractor.extract("   ");

        assertSame(ProductExtraction.EMPTY, result);
        verify(brandExtractor, never()).find(anyString());
        verify(dictionaryClassifier, never()).classify(anyString());
    }

    @Test
    void nullDescriptionReturnsEmpty() {
        assertSame(ProductExtraction.EMPTY, extractor.extract(null));
    }

    @Test
    void dictionaryHitProducesCorrectFields() {
        when(brandExtractor.find(anyString())).thenReturn("Tio João");
        when(dictionaryClassifier.classify(anyString())).thenReturn(
                new DictEntry("Arroz", null, ProductCategory.GROCERIES, CategorizationSource.DICTIONARY));

        var result = extractor.extract("ARROZ TIO J TP1 5KG");

        assertEquals("Arroz", result.genericName());
        assertEquals("Tio João", result.brand());
        assertEquals(ProductCategory.GROCERIES, result.category());
        assertEquals(new BigDecimal("5"), result.packSize());
        assertEquals("KG", result.packUnit());
        assertEquals(CategorizationSource.DICTIONARY, result.categorizationSource());
    }

    @Test
    void dictionaryMiss_producesNoneSource() {
        when(brandExtractor.find(anyString())).thenReturn(null);
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);

        var result = extractor.extract("PRODUTO XYZ DESCONHECIDO");

        assertNull(result.genericName());
        assertNull(result.category());
        assertEquals(CategorizationSource.NONE, result.categorizationSource());
    }

    @Test
    void packSizeAndBrandExtractedEvenWithDictionaryMiss() {
        when(brandExtractor.find(anyString())).thenReturn("Veja");
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);

        var result = extractor.extract("LIMP COZ VEJA LIMAO SQ500ML PROM");

        assertEquals("Veja", result.brand());
        assertEquals(new BigDecimal("500"), result.packSize());
        assertEquals("ML", result.packUnit());
    }

    @Test
    void dictBrandOverridesTakesPrecedenceOverRegistryBrand() {
        // DictEntry supplies brand → BrandExtractor is never consulted
        when(dictionaryClassifier.classify(anyString())).thenReturn(
                new DictEntry("Arroz", "BrandFromDict", ProductCategory.GROCERIES, CategorizationSource.DICTIONARY));

        var result = extractor.extract("ARROZ MARCA 5KG");

        assertEquals("BrandFromDict", result.brand(), "dict-supplied brand wins");
    }
}
