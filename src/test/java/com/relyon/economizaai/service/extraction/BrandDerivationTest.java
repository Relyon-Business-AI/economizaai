package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.EanCatalogRepository;
import com.relyon.economizaai.repository.EanCatalogRepository.BrandOccurrence;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandDerivationTest {

    @Mock private LearnedDictionaryRepository learnedRepository;
    @Mock private ProductRepository productRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;
    @Mock private BrandExtractor brandExtractor;
    @Mock private CuratedDictionaryEntryRepository curatedRepository;
    @Mock private BrandRegistryEntryRepository brandRepository;
    @Mock private CategorizationBenchmarkEntryRepository benchmarkRepository;
    @Mock private EanCatalogRepository eanCatalogRepository;

    @InjectMocks private CategorizerAdminService categorizerAdminService;

    private BrandOccurrence occurrence(String brand, long count) {
        return new BrandOccurrence() {
            @Override public String getBrand() { return brand; }
            @Override public long getOccurrences() { return count; }
        };
    }

    @Test
    void derive_groupsVariantsByNormalizedKeyAndPicksMostFrequentDisplay() {
        when(eanCatalogRepository.countByBrandBrazilOnly()).thenReturn(List.of(
                occurrence("Nestlé", 40),
                occurrence("NESTLE", 3),
                occurrence("nestle", 2)));
        when(brandRepository.findByNormalizedKey("nestle")).thenReturn(Optional.empty());

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(2);

        assertEquals(1, outcome.created());
        var entryCaptor = ArgumentCaptor.forClass(BrandRegistryEntry.class);
        verify(brandRepository).save(entryCaptor.capture());
        assertEquals("nestle", entryCaptor.getValue().getNormalizedKey());
        assertEquals("Nestlé", entryCaptor.getValue().getDisplayName(), "most frequent variant wins");
        verify(brandExtractor).reload();
    }

    @Test
    void derive_skipsKeysThatAreKnownProductWords() {
        // "tomate" is a curated dictionary keyword (a generic product term), so it
        // must never become a brand even if OFF lists it as one.
        when(curatedRepository.findAll()).thenReturn(List.of(
                com.relyon.economizaai.model.CuratedDictionaryEntry.builder()
                        .keyword("tomate").category(com.relyon.economizaai.model.enums.ProductCategory.PRODUCE).build()));
        when(eanCatalogRepository.countByBrandBrazilOnly()).thenReturn(List.of(
                occurrence("Tomate", 12),
                occurrence("Piraquê", 8)));
        when(brandRepository.findByNormalizedKey("piraque")).thenReturn(Optional.empty());

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(3);

        assertEquals(1, outcome.created(), "only the real brand (Piraquê) is created, not 'tomate'");
        var captor = ArgumentCaptor.forClass(BrandRegistryEntry.class);
        verify(brandRepository).save(captor.capture());
        assertEquals("piraque", captor.getValue().getNormalizedKey());
    }

    @Test
    void derive_dropsBrandsBelowThreshold() {
        when(eanCatalogRepository.countByBrandBrazilOnly()).thenReturn(List.of(
                occurrence("Marca Obscura", 1)));

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(2);

        assertEquals(0, outcome.created());
        assertEquals(1, outcome.belowThreshold());
        verify(brandRepository, never()).save(any());
        verify(brandExtractor, never()).reload();
    }

    @Test
    void derive_neverOverwritesExistingRegistryEntries() {
        when(eanCatalogRepository.countByBrandBrazilOnly()).thenReturn(List.of(
                occurrence("Tio João", 25)));
        when(brandRepository.findByNormalizedKey("tio joao")).thenReturn(Optional.of(
                BrandRegistryEntry.builder().normalizedKey("tio joao").displayName("Tio João").build()));

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(2);

        assertEquals(0, outcome.created());
        assertEquals(1, outcome.skippedExisting());
        verify(brandRepository, never()).save(any());
    }

    @Test
    void derive_skipsJunkKeys() {
        when(eanCatalogRepository.countByBrandBrazilOnly()).thenReturn(List.of(
                occurrence("7", 50),            // numeric-only
                occurrence("de", 50),           // stopword — would false-match "BOLACHA DE MANTEIGA"
                occurrence("nat", 50),          // stopword + too short
                occurrence("barra", 50),        // stopword
                occurrence("abc", 50)));        // 3 chars, below the 4-char minimum

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(1);

        assertEquals(0, outcome.created());
        verify(brandRepository, never()).save(any());
    }

    @Test
    void derive_tagsEntriesAsDerived() {
        when(eanCatalogRepository.countByBrandBrazilOnly()).thenReturn(List.of(occurrence("Nestlé", 40)));
        when(brandRepository.findByNormalizedKey("nestle")).thenReturn(Optional.empty());

        categorizerAdminService.deriveBrandsFromEanCatalog(2);

        var captor = ArgumentCaptor.forClass(BrandRegistryEntry.class);
        verify(brandRepository).save(captor.capture());
        assertEquals("DERIVED", captor.getValue().getSource());
    }
}
