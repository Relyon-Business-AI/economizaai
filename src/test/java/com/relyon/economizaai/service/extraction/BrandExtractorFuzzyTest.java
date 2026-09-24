package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrandExtractorFuzzyTest {

    private BrandExtractor loadedWith(String... keyThenName) {
        var entries = new ArrayList<BrandRegistryEntry>();
        for (var pair = 0; pair < keyThenName.length; pair += 2) {
            entries.add(BrandRegistryEntry.builder()
                    .normalizedKey(keyThenName[pair])
                    .displayName(keyThenName[pair + 1])
                    .build());
        }
        var repository = mock(BrandRegistryEntryRepository.class);
        when(repository.findAll()).thenReturn(entries);
        var extractor = new BrandExtractor(repository);
        extractor.reload();
        return extractor;
    }

    @Test
    void abbreviationMatchesDonaBenta() {
        var extractor = loadedWith("dona benta", "Dona Benta");
        assertEquals("Dona Benta", extractor.find("FERM BIO D BENTA 10G"));
    }

    @Test
    void abbreviationRequiresAnchorFullWord() {
        // "d b" is too weak: no full-word anchor and below the min-chars floor.
        var extractor = loadedWith("dona benta", "Dona Benta");
        assertNull(extractor.find("FERM BIO D B 10G"));
    }

    @Test
    void abbreviationDoesNotCrossBrands() {
        // "d benta" must not resolve to an unrelated brand starting with 'd'.
        var extractor = loadedWith("dona benta", "Dona Benta", "danone", "Danone");
        assertEquals("Dona Benta", extractor.find("FERM BIO D BENTA 10G"));
    }

    @Test
    void typoMatchesLongSingleToken() {
        var extractor = loadedWith("nestle", "Nestlé");
        assertEquals("Nestlé", extractor.find("NESTLEE ACHOCOLATADO"));
    }

    @Test
    void typoIgnoresShortBrands() {
        // "veja" (4 chars) is below the typo floor — "vega" must not match it.
        var extractor = loadedWith("veja", "Veja");
        assertNull(extractor.find("VEGA LIMPADOR"));
    }

    @Test
    void exactStillWinsAndIsPreferred() {
        var extractor = loadedWith("dona benta", "Dona Benta");
        assertEquals("Dona Benta", extractor.find("FARINHA DONA BENTA 1KG"));
    }

    @Test
    void disabledFallsBackToExactOnly() {
        var extractor = loadedWith("dona benta", "Dona Benta");
        ReflectionTestUtils.setField(extractor, "fuzzyEnabled", false);
        assertNull(extractor.find("FERM BIO D BENTA 10G"));
        assertEquals("Dona Benta", extractor.find("FARINHA DONA BENTA 1KG"));
    }

    @Test
    void unknownStaysNull() {
        var extractor = loadedWith("dona benta", "Dona Benta");
        assertNull(extractor.find("PRODUTO XYZ ALIENIGENA"));
    }
}
