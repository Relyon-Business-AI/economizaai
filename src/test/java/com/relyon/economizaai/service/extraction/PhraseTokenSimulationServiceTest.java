package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhraseTokenSimulationServiceTest {

    private ReceiptItemRepository receiptItemRepository;
    private CategorizationBenchmarkEntryRepository benchmarkRepository;
    private PhraseTokenSimulationService service;

    @BeforeEach
    void setUp() {
        // A 3-token curated key ("bio d benta") only matches when the phrase
        // window is >= 3, so the window's effect is measurable; "arroz" (1 token)
        // matches at any window and acts as the always-covered baseline.
        var curatedRepository = mock(CuratedDictionaryEntryRepository.class);
        when(curatedRepository.findAll()).thenReturn(List.of(
                curated("arroz", "Arroz", ProductCategory.GROCERIES),
                curated("bio d benta", "Fermento Biológico", ProductCategory.GROCERIES)));
        var dictionaryClassifier = new DictionaryClassifier(curatedRepository);
        dictionaryClassifier.reloadCuratedEntries();

        var brandExtractor = SeedFixtures.loadedBrandExtractor();

        receiptItemRepository = mock(ReceiptItemRepository.class);
        when(receiptItemRepository.topUnmatchedDescriptions(any(Pageable.class))).thenReturn(List.of(
                new Object[]{"ferm bio d benta 10g", 5L},
                new Object[]{"arroz tio joao 5kg", 3L}));

        benchmarkRepository = mock(CategorizationBenchmarkEntryRepository.class);
        when(benchmarkRepository.findAll()).thenReturn(List.of(
                golden("ferm bio d benta 10g", ProductCategory.GROCERIES),
                golden("arroz tio joao 5kg", ProductCategory.GROCERIES)));

        service = new PhraseTokenSimulationService(
                dictionaryClassifier, brandExtractor, receiptItemRepository, benchmarkRepository);
    }

    @Test
    void widerWindowRecoversTheThreeTokenKeyword() {
        var response = service.simulate(2, 3, 100);

        var atTwo = response.rows().get(0);
        var atThree = response.rows().get(1);

        // At N=2 only "arroz" matches; at N=3 the "bio d benta" phrase also matches.
        assertEquals(2, atTwo.tokens());
        assertEquals(1, atTwo.distinctCovered());
        assertEquals(3L, atTwo.itemsRecovered());

        assertEquals(3, atThree.tokens());
        assertEquals(2, atThree.distinctCovered());
        assertEquals(8L, atThree.itemsRecovered());
        assertEquals(1, atThree.newlyCoveredVsBaseline());
        assertEquals(5L, atThree.newItemsRecoveredVsBaseline());
        assertTrue(atThree.newlyCoveredExamples().contains("ferm bio d benta 10g"));
    }

    @Test
    void goldenAccuracyImprovesWithWiderWindow() {
        var response = service.simulate(2, 3, 100);

        // "arroz" golden row is correct at both windows; the fermento row only at N>=3.
        assertEquals(1, response.rows().get(0).goldenCategoryCorrect());
        assertEquals(2, response.rows().get(1).goldenCategoryCorrect());
        assertEquals(2, response.goldenSetSize());
    }

    @Test
    void reportsBaselineAndSampleTotals() {
        var response = service.simulate(3, 3, 100);
        assertEquals(3, response.baselineTokens());
        assertEquals(2, response.distinctUnmatchedSampled());
        assertEquals(8L, response.unmatchedItemsSampled());
    }

    private static CuratedDictionaryEntry curated(String keyword, String genericName, ProductCategory category) {
        return CuratedDictionaryEntry.builder()
                .keyword(keyword)
                .genericName(genericName)
                .category(category)
                .build();
    }

    private static CategorizationBenchmarkEntry golden(String description, ProductCategory expectedCategory) {
        return CategorizationBenchmarkEntry.builder()
                .description(description)
                .expectedCategory(expectedCategory)
                .build();
    }
}
