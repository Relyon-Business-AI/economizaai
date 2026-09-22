package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.extraction.DictionaryClassifier.DictEntry;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import com.relyon.economizaai.service.extraction.ml.MlPrediction;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the cascade composition and the ML gating in {@link ProductExtractor}
 * with fully-mocked collaborators, complementing the fixture-backed
 * {@code ProductExtractorTest}. Here brand/dictionary/ML are all mocks so each
 * gating branch can be driven deterministically.
 */
@ExtendWith(MockitoExtension.class)
class ProductExtractorCascadeTest {

    @Mock
    private BrandExtractor brandExtractor;

    @Mock
    private DictionaryClassifier dictionaryClassifier;

    @Mock
    private MlClassifierService mlClassifier;

    @InjectMocks
    private ProductExtractor extractor;

    @BeforeEach
    void defaultThreshold() {
        lenient().when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);
    }

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
    void dictionaryHitWinsAndMlIsNotConsulted() {
        when(brandExtractor.find(anyString())).thenReturn("Tio João");
        when(dictionaryClassifier.classify(anyString())).thenReturn(
                new DictEntry("Arroz", ProductCategory.GROCERIES, CategorizationSource.DICTIONARY));

        var result = extractor.extract("ARROZ TIO J TP1 5KG");

        assertEquals("Arroz", result.genericName());
        assertEquals("Tio João", result.brand());
        assertEquals(ProductCategory.GROCERIES, result.category());
        assertEquals(new BigDecimal("5"), result.packSize());
        assertEquals("KG", result.packUnit());
        assertEquals(CategorizationSource.DICTIONARY, result.categorizationSource());
        verify(mlClassifier, never()).predictCategory(anyString());
        verify(mlClassifier, never()).predictGenericName(anyString());
    }

    @Test
    void mlGatedOffWhenNotReady_dictionaryMissProducesNoneSource() {
        when(brandExtractor.find(anyString())).thenReturn(null);
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.isReady()).thenReturn(false);

        var result = extractor.extract("PRODUTO XYZ DESCONHECIDO");

        assertNull(result.genericName());
        assertNull(result.category());
        assertEquals(CategorizationSource.NONE, result.categorizationSource());
        verify(mlClassifier, never()).predictCategory(anyString());
        verify(mlClassifier, never()).predictGenericName(anyString());
    }

    @Test
    void mlGatedOffWhenApplyDisabled_evenIfReady() {
        when(brandExtractor.find(anyString())).thenReturn(null);
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.isCategoryApplyEnabled()).thenReturn(false);

        var result = extractor.extract("PRODUTO XYZ DESCONHECIDO");

        assertEquals(CategorizationSource.NONE, result.categorizationSource());
        verify(mlClassifier, never()).predictCategory(anyString());
        verify(mlClassifier, never()).predictGenericName(anyString());
    }

    @Test
    void mlAppliesConfidentCategoryWhenEnabledAndDictionaryMissed() {
        when(brandExtractor.find(anyString())).thenReturn(null);
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.isCategoryApplyEnabled()).thenReturn(true);
        when(mlClassifier.predictCategory(anyString()))
                .thenReturn(new MlPrediction<>(ProductCategory.CLEANING, 0.92));
        when(mlClassifier.predictGenericName(anyString()))
                .thenReturn(MlPrediction.empty());

        var result = extractor.extract("ITEM MISTERIOSO LIMPA TUDO");

        assertEquals(ProductCategory.CLEANING, result.category());
        assertEquals(CategorizationSource.ML, result.categorizationSource());
        assertNull(result.genericName());
    }

    @Test
    void mlDoesNotApplyCategoryBelowThreshold() {
        when(brandExtractor.find(anyString())).thenReturn(null);
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.isCategoryApplyEnabled()).thenReturn(true);
        when(mlClassifier.predictCategory(anyString()))
                .thenReturn(new MlPrediction<>(ProductCategory.CLEANING, 0.40));
        when(mlClassifier.predictGenericName(anyString()))
                .thenReturn(MlPrediction.empty());

        var result = extractor.extract("ITEM MISTERIOSO");

        assertNull(result.category());
        assertEquals(CategorizationSource.NONE, result.categorizationSource());
    }

    @Test
    void mlAppliesConfidentGenericNameWhenDictionaryMissed() {
        when(brandExtractor.find(anyString())).thenReturn(null);
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.isCategoryApplyEnabled()).thenReturn(true);
        when(mlClassifier.predictCategory(anyString()))
                .thenReturn(MlPrediction.empty());
        when(mlClassifier.predictGenericName(anyString()))
                .thenReturn(new MlPrediction<>("Sabonete", 0.88));

        var result = extractor.extract("ITEM MISTERIOSO");

        assertEquals("Sabonete", result.genericName());
        assertEquals(CategorizationSource.ML, result.categorizationSource());
        assertNull(result.category());
    }

    @Test
    void mlGenericNameDoesNotOverrideDictionarySourceWhenCategoryCameFromDictionary() {
        // Dictionary supplied a category (so source=DICTIONARY) but no genericName.
        when(brandExtractor.find(anyString())).thenReturn(null);
        when(dictionaryClassifier.classify(anyString())).thenReturn(
                new DictEntry(null, ProductCategory.GROCERIES, CategorizationSource.DICTIONARY));
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.isCategoryApplyEnabled()).thenReturn(true);
        when(mlClassifier.predictGenericName(anyString()))
                .thenReturn(new MlPrediction<>("Feijao", 0.95));

        var result = extractor.extract("ITEM");

        assertEquals(ProductCategory.GROCERIES, result.category());
        assertEquals("Feijao", result.genericName());
        // Source stays DICTIONARY because it wasn't NONE when the ML genericName landed.
        assertEquals(CategorizationSource.DICTIONARY, result.categorizationSource());
        // Category was already set by the dictionary, so ML category isn't consulted.
        verify(mlClassifier, never()).predictCategory(anyString());
    }

    @Test
    void mlBelowThresholdGenericNameLeavesGenericNull() {
        when(brandExtractor.find(anyString())).thenReturn(null);
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.isCategoryApplyEnabled()).thenReturn(true);
        when(mlClassifier.predictCategory(anyString()))
                .thenReturn(MlPrediction.empty());
        when(mlClassifier.predictGenericName(anyString()))
                .thenReturn(new MlPrediction<>("Talvez", 0.10));

        var result = extractor.extract("ITEM MISTERIOSO");

        assertNull(result.genericName());
        assertEquals(CategorizationSource.NONE, result.categorizationSource());
    }

    @Test
    void packSizeAndBrandAlwaysExtractedRegardlessOfMl() {
        when(brandExtractor.find(anyString())).thenReturn("Veja");
        when(dictionaryClassifier.classify(anyString())).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.isReady()).thenReturn(false);

        var result = extractor.extract("LIMP COZ VEJA LIMAO SQ500ML PROM");

        assertEquals("Veja", result.brand());
        assertEquals(new BigDecimal("500"), result.packSize());
        assertEquals("ML", result.packUnit());
    }
}
