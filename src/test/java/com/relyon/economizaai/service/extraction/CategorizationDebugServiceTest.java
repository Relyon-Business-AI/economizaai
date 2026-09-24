package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.extraction.DictionaryClassifier.DictEntry;
import com.relyon.economizaai.service.extraction.ml.MlClassifierService;
import com.relyon.economizaai.service.extraction.ml.MlPrediction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizationDebugServiceTest {

    @Mock private ProductExtractor productExtractor;
    @Mock private DictionaryClassifier dictionaryClassifier;
    @Mock private MlClassifierService mlClassifier;
    @InjectMocks private CategorizationDebugService service;

    @Test
    void explain_exposesFinalDecisionPlusPerLayerBreakdown() {
        when(productExtractor.extract("Batata Frita")).thenReturn(new ProductExtraction(
                "Batata", null, new BigDecimal("100"), "G", ProductCategory.PRODUCE, CategorizationSource.DICTIONARY));
        when(dictionaryClassifier.classify("Batata Frita")).thenReturn(
                new DictEntry("Batata", null, ProductCategory.PRODUCE, CategorizationSource.DICTIONARY));
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.isCategoryApplyEnabled()).thenReturn(false);
        when(mlClassifier.predictCategory("Batata Frita")).thenReturn(new MlPrediction<>(ProductCategory.GROCERIES, 0.40));
        when(mlClassifier.predictGenericName("Batata Frita")).thenReturn(new MlPrediction<>("Salgadinho", 0.40));

        var r = service.explain("Batata Frita");

        assertEquals("Batata Frita", r.input());
        assertEquals(ProductCategory.PRODUCE, r.category());
        assertEquals(CategorizationSource.DICTIONARY, r.source());
        assertEquals(ProductCategory.PRODUCE, r.dictionary().category());
        // ML guessed GROCERIES but below the 0.75 threshold → wouldn't have applied
        assertEquals("GROCERIES", r.mlCategory().label());
        assertEquals(0.40, r.mlCategory().confidence());
        assertFalse(r.mlCategory().meetsThreshold());
        assertTrue(r.mlReady());
        assertFalse(r.mlApplied(), "ML is gated off → not applied to the live result");
    }

    @Test
    void mlPredict_returnsModelOnlyView() {
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.predictCategory("Leite")).thenReturn(new MlPrediction<>(ProductCategory.MEAT_DAIRY, 0.91));
        when(mlClassifier.predictGenericName("Leite")).thenReturn(new MlPrediction<>("Leite", 0.88));

        var r = service.mlPredict("Leite");

        assertEquals("Leite", r.input());
        assertEquals("MEAT_DAIRY", r.category().label());
        assertEquals(0.91, r.category().confidence());
        assertTrue(r.category().meetsThreshold());
        assertTrue(r.ready());
    }

    @Test
    void explain_handlesNoMlPrediction() {
        when(productExtractor.extract("Xyz")).thenReturn(ProductExtraction.EMPTY);
        when(dictionaryClassifier.classify("Xyz")).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);
        when(mlClassifier.predictCategory("Xyz")).thenReturn(MlPrediction.empty());
        when(mlClassifier.predictGenericName("Xyz")).thenReturn(MlPrediction.empty());

        var r = service.explain("Xyz");

        assertEquals(CategorizationSource.NONE, r.source());
        assertEquals(null, r.mlCategory().label());
        assertEquals(null, r.mlCategory().confidence());
        assertFalse(r.mlCategory().meetsThreshold());
    }

    @Test
    void explainAll_skipsNulls() {
        when(productExtractor.extract("A")).thenReturn(ProductExtraction.EMPTY);
        when(dictionaryClassifier.classify("A")).thenReturn(DictEntry.EMPTY);
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);
        when(mlClassifier.predictCategory("A")).thenReturn(MlPrediction.empty());
        when(mlClassifier.predictGenericName("A")).thenReturn(MlPrediction.empty());

        var results = service.explainAll(Arrays.asList("A", null));

        assertEquals(1, results.size());
    }
}
