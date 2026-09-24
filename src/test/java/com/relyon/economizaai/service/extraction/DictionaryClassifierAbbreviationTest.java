package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DictionaryClassifierAbbreviationTest {

    private DictionaryClassifier loadedWith(CuratedDictionaryEntry... entries) {
        var repository = mock(CuratedDictionaryEntryRepository.class);
        when(repository.findAll()).thenReturn(new ArrayList<>(List.of(entries)));
        var classifier = new DictionaryClassifier(repository);
        classifier.reloadCuratedEntries();
        ReflectionTestUtils.setField(classifier, "abbreviationFallbackEnabled", true); // default is off
        return classifier;
    }

    private static CuratedDictionaryEntry curated(String keyword, String genericName, ProductCategory category) {
        return CuratedDictionaryEntry.builder().keyword(keyword).genericName(genericName).category(category).build();
    }

    @Test
    void abbreviationMatchesShampooKeyword() {
        var classifier = loadedWith(curated("shampoo", "Shampoo", ProductCategory.PERSONAL_CARE));
        var hit = classifier.classify("SHAMP PALMOLIVE NAT DETOX350ML");
        assertEquals("Shampoo", hit.genericName());
        assertEquals(ProductCategory.PERSONAL_CARE, hit.category());
    }

    @Test
    void exactMatchStillWinsOverAbbreviation() {
        var classifier = loadedWith(
                curated("shampoo", "Shampoo", ProductCategory.PERSONAL_CARE),
                curated("shamp", "Shampoo Abrev", ProductCategory.PERSONAL_CARE));
        assertEquals("Shampoo Abrev", classifier.classify("SHAMP SEDA").genericName());
    }

    @Test
    void ambiguousCategoriesAreSkipped() {
        // "leit" prefixes both keywords, which disagree on category → no match.
        var classifier = loadedWith(
                curated("leite", "Leite", ProductCategory.MEAT_DAIRY),
                curated("leitura", "Revista", ProductCategory.OTHER));
        assertNull(classifier.classify("LEIT X").category());
    }

    @Test
    void agreementAcrossCandidatesStillMatches() {
        // Both candidates agree on category → safe to match.
        var classifier = loadedWith(
                curated("shampoo", "Shampoo", ProductCategory.PERSONAL_CARE),
                curated("shampoo bebe", "Shampoo", ProductCategory.PERSONAL_CARE));
        assertEquals(ProductCategory.PERSONAL_CARE, classifier.classify("SHAMP JOHNSON").category());
    }

    @Test
    void shortSingleTokenIsRejected() {
        // "gel" (3 chars) is below the single-token floor — too ambiguous.
        var classifier = loadedWith(curated("geleia", "Geleia", ProductCategory.GROCERIES));
        assertNull(classifier.classify("GEL DENTAL SORRISO").category());
    }

    @Test
    void multiTokenAbbreviationMatches() {
        var classifier = loadedWith(curated("fermento biologico", "Fermento Biológico", ProductCategory.GROCERIES));
        var hit = classifier.classify("FERM BIOL D BENTA 10G");
        assertEquals("Fermento Biológico", hit.genericName());
    }

    @Test
    void disabledFallsBackToExactOnly() {
        var classifier = loadedWith(curated("shampoo", "Shampoo", ProductCategory.PERSONAL_CARE));
        ReflectionTestUtils.setField(classifier, "abbreviationFallbackEnabled", false);
        assertNull(classifier.classify("SHAMP PALMOLIVE").category());
        assertEquals("Shampoo", classifier.classify("SHAMPOO PALMOLIVE").genericName());
    }
}
