package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

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
        // Curated dictionary supplies the generic-word blocklist ("biscoito", "arroz").
        var curatedRepository = mock(CuratedDictionaryEntryRepository.class);
        when(curatedRepository.findAll()).thenReturn(List.of(
                CuratedDictionaryEntry.builder().keyword("biscoito").category(ProductCategory.GROCERIES).build(),
                CuratedDictionaryEntry.builder().keyword("arroz").category(ProductCategory.GROCERIES).build()));
        var extractor = new BrandExtractor(repository, curatedRepository);
        extractor.reload();
        ReflectionTestUtils.setField(extractor, "fuzzyEnabled", true); // default is off
        return extractor;
    }

    // ── Anchored multi-token abbreviation ───────────────────────────────────────

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
        var extractor = loadedWith("dona benta", "Dona Benta", "danone", "Danone");
        assertEquals("Dona Benta", extractor.find("FERM BIO D BENTA 10G"));
    }

    // ── Single-token prefix ─────────────────────────────────────────────────────

    @Test
    void singleTokenPrefixMatchesLongBrand() {
        var extractor = loadedWith("predilecta", "Predilecta");
        assertEquals("Predilecta", extractor.find("MILHO PREDILEC 170G"));
    }

    @Test
    void singleTokenPrefixRejectsCommonWord() {
        // "verde" is a common descriptor — must never resolve to a brand it prefixes.
        var extractor = loadedWith("verdemar", "Verdemar");
        assertNull(extractor.find("TEMPERO VERDE"));
    }

    @Test
    void singleTokenPrefixRejectsTooCloseLength() {
        // "essencia" → "essencial" differs by 1 char: below the min-extra floor.
        var extractor = loadedWith("essencial", "Essencial");
        assertNull(extractor.find("ESSENCIA OETKER BAUNILHA 30ML"));
    }

    @Test
    void singleTokenPrefixRejectsShortPrefix() {
        // A 5-char prefix is below the single-token floor (blocks noisy short matches).
        var extractor = loadedWith("pratos", "Pratos");
        assertNull(extractor.find("PRATO FUNDO"));
    }

    @Test
    void singleTokenPrefixRejectsGenericProductWord() {
        // "biscoito" is a curated product word — must not resolve to the brand "Biscoitone".
        var extractor = loadedWith("biscoitone", "Biscoitone");
        assertNull(extractor.find("BISCOITO RECHEADO CHOCOLATE"));
    }

    // ── Guards / plumbing ───────────────────────────────────────────────────────

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
