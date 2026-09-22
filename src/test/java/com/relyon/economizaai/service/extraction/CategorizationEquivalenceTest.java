package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guarantees the /classify dry-run produces the EXACT same categorization as
 * real NF ingestion. Ingestion calls {@link ProductExtractor#extract} on the
 * item's raw description (see CanonicalizationService); the debug endpoint runs
 * the same extractor on the same string. This test asserts every field of
 * {@link CategorizationDebugService#explain} matches {@code extract()} for a
 * range of real-world descriptions — including the abbreviated, messy forms
 * NFC-e emits — so the simulator can never silently drift from ingestion.
 *
 * <p>(The only post-extraction difference in ingestion is EAN/dedup linking to
 * a PRE-EXISTING product, which inherits that product's stored category — that's
 * product matching, not categorization of the description, and is exactly what
 * the re-categorize batch corrects.)
 */
@SpringBootTest
@ActiveProfiles("test")
class CategorizationEquivalenceTest {

    @Autowired private ProductExtractor productExtractor;
    @Autowired private CategorizationDebugService debugService;
    @Autowired private CuratedDictionaryEntryRepository curatedRepository;
    @Autowired private BrandRegistryEntryRepository brandRepository;
    @Autowired private DictionaryClassifier dictionaryClassifier;
    @Autowired private BrandExtractor brandExtractor;

    @BeforeEach
    void seedDictionaryAndBrands() {
        curatedRepository.deleteAll();
        brandRepository.deleteAll();
        curatedRepository.saveAll(SeedFixtures.curatedEntries());
        brandRepository.saveAll(SeedFixtures.brandEntries());
        dictionaryClassifier.reloadCuratedEntries();
        brandExtractor.reload();
    }

    private static final List<String> DESCRIPTIONS = List.of(
            "MILHO VERDE 200G",
            "BISC MANTEIGA 200G",
            "BATATA FRITA AIR FRYER",
            "SALG LAYS CLASSICA 96G",
            "ARROZ TIO J TP1 5KG",
            "LEITE ITALAC INT 1L",
            "DETERGENTE YPE 500ML",
            "xyz produto inexistente 123",
            "");

    @Test
    void classify_matchesIngestionExtractionExactly() {
        for (var description : DESCRIPTIONS) {
            var extracted = productExtractor.extract(description);
            var explained = debugService.explain(description);

            assertEquals(extracted.category(), explained.category(), "category for '" + description + "'");
            assertEquals(extracted.genericName(), explained.genericName(), "genericName for '" + description + "'");
            assertEquals(extracted.brand(), explained.brand(), "brand for '" + description + "'");
            assertEquals(extracted.packSize(), explained.packSize(), "packSize for '" + description + "'");
            assertEquals(extracted.packUnit(), explained.packUnit(), "packUnit for '" + description + "'");
            assertEquals(extracted.categorizationSource(), explained.source(), "source for '" + description + "'");
        }
    }
}
