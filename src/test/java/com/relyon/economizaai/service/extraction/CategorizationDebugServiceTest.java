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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizationDebugServiceTest {

    @Mock private ProductExtractor productExtractor;
    @Mock private DictionaryClassifier dictionaryClassifier;
    @InjectMocks private CategorizationDebugService service;

    @Test
    void explain_exposesFinalDecisionPlusPerLayerBreakdown() {
        when(productExtractor.extract("Batata Frita")).thenReturn(new ProductExtraction(
                "Batata", null, new BigDecimal("100"), "G", ProductCategory.PRODUCE, CategorizationSource.DICTIONARY));
        when(dictionaryClassifier.classify("Batata Frita")).thenReturn(
                new DictEntry("Batata", null, ProductCategory.PRODUCE, CategorizationSource.DICTIONARY));

        var r = service.explain("Batata Frita");

        assertEquals("Batata Frita", r.input());
        assertEquals(ProductCategory.PRODUCE, r.category());
        assertEquals(CategorizationSource.DICTIONARY, r.source());
        assertEquals(ProductCategory.PRODUCE, r.dictionary().category());
        assertEquals("Batata", r.genericName());
    }

    @Test
    void explain_handlesNoDictionaryMatch() {
        when(productExtractor.extract("Xyz")).thenReturn(ProductExtraction.EMPTY);
        when(dictionaryClassifier.classify("Xyz")).thenReturn(DictEntry.EMPTY);

        var r = service.explain("Xyz");

        assertEquals(CategorizationSource.NONE, r.source());
        assertNull(r.dictionary().category());
    }

    @Test
    void explainAll_skipsNulls() {
        when(productExtractor.extract("A")).thenReturn(ProductExtraction.EMPTY);
        when(dictionaryClassifier.classify("A")).thenReturn(DictEntry.EMPTY);

        var results = service.explainAll(Arrays.asList("A", null));

        assertEquals(1, results.size());
    }
}
