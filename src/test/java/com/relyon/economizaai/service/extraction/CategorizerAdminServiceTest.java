package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.LearnedDictionaryEntry;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.EanCatalogRepository;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizerAdminServiceTest {

    @Mock private LearnedDictionaryRepository learnedRepository;
    @Mock private ProductRepository productRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;
    @Mock private BrandExtractor brandExtractor;
    @Mock private CuratedDictionaryEntryRepository curatedRepository;
    @Mock private BrandRegistryEntryRepository brandRepository;
    @Mock private CategorizationBenchmarkEntryRepository benchmarkRepository;
    @Mock private EanCatalogRepository eanCatalogRepository;
    @Mock private CanonicalizationService canonicalizationService;

    @InjectMocks private CategorizerAdminService service;

    private CuratedDictionaryEntry curated() {
        return CuratedDictionaryEntry.builder()
                .id(UUID.randomUUID()).keyword("racao").genericName("Ração")
                .category(ProductCategory.PET_SUPPLIES).origin("ADMIN").build();
    }

    @Test
    void listCurated_withoutQuery_usesFindAll() {
        var pageable = PageRequest.of(0, 50);
        when(curatedRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(curated())));

        var page = service.listCurated("  ", pageable);

        assertEquals(1, page.getTotalElements());
        assertEquals("racao", page.getContent().get(0).keyword());
        verify(curatedRepository, never()).findByKeywordContainingIgnoreCase(any(), any());
    }

    @Test
    void listCurated_withQuery_usesSubstringSearch() {
        var pageable = PageRequest.of(0, 50);
        when(curatedRepository.findByKeywordContainingIgnoreCase(eq("rac"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(curated())));

        var page = service.listCurated("RAC", pageable);

        assertEquals(1, page.getTotalElements());
        verify(curatedRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void importCuratedEntries_normalizesAccentedKeyword() {
        when(curatedRepository.findByKeyword("acucar")).thenReturn(Optional.empty());
        var saved = ArgumentCaptor.forClass(CuratedDictionaryEntry.class);

        var outcome = service.importCuratedEntries(List.of(
                new CategorizerAdminService.CuratedImportRequest("AÇÚCAR", "Açúcar", null, ProductCategory.GROCERIES)));

        assertEquals(1, outcome.imported());
        verify(curatedRepository).save(saved.capture());
        // Key stored fully normalized (accent-stripped, lowercased) so it matches at lookup.
        assertEquals("acucar", saved.getValue().getKeyword());
        // Display value kept as typed.
        assertEquals("Açúcar", saved.getValue().getGenericName());
    }

    @Test
    void importCuratedEntries_keepsBrandAndGenericDisplayButNormalizesKeyword() {
        when(curatedRepository.findByKeyword("cafe pilao")).thenReturn(Optional.empty());
        var saved = ArgumentCaptor.forClass(CuratedDictionaryEntry.class);

        service.importCuratedEntries(List.of(new CategorizerAdminService.CuratedImportRequest(
                "Café Pilão", "Café", "Pilão", ProductCategory.GROCERIES)));

        verify(curatedRepository).save(saved.capture());
        assertEquals("cafe pilao", saved.getValue().getKeyword());   // match key normalized
        assertEquals("Café", saved.getValue().getGenericName());       // display kept
        assertEquals("Pilão", saved.getValue().getBrand());           // display kept
    }

    @Test
    void importBrands_normalizesKeyAccentsNotOnlyLowercase() {
        when(brandRepository.findByNormalizedKey("nescafe")).thenReturn(Optional.empty());
        var saved = ArgumentCaptor.forClass(BrandRegistryEntry.class);

        service.importBrands(List.of(new CategorizerAdminService.BrandImportRequest("Nescafé", "Nescafé")));

        verify(brandRepository).save(saved.capture());
        assertEquals("nescafe", saved.getValue().getNormalizedKey()); // key accent-stripped
        assertEquals("Nescafé", saved.getValue().getDisplayName());   // display kept
    }

    @Test
    void bulkImport_learnedTokenIsNormalizedAccentStripped() {
        when(learnedRepository.findByNormalizedTokenIn(any())).thenReturn(List.of());
        when(learnedRepository.findAll()).thenReturn(List.of());
        var saved = ArgumentCaptor.forClass(List.class);

        service.bulkImport(List.of(new CategorizerAdminService.DictionaryImportRequest(
                "Açaí", "Açaí", ProductCategory.GROCERIES, 999)));

        verify(learnedRepository).saveAll(saved.capture());
        var entries = (List<LearnedDictionaryEntry>) saved.getValue();
        assertEquals(1, entries.size());
        assertEquals("acai", entries.get(0).getNormalizedToken()); // token accent-stripped
    }

    @Test
    void listCurated_normalizesAccentedQueryBeforeSearch() {
        var pageable = PageRequest.of(0, 50);
        when(curatedRepository.findByKeywordContainingIgnoreCase(eq("acucar"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(curated())));

        service.listCurated("AÇÚCAR", pageable);

        // The accented query is normalized so it matches the accent-stripped stored key.
        verify(curatedRepository).findByKeywordContainingIgnoreCase(eq("acucar"), any(Pageable.class));
    }

    @Test
    void deleteCurated_deletesAndHotReloads() {
        var id = UUID.randomUUID();

        service.deleteCurated(id);

        verify(curatedRepository).deleteById(id);
        verify(dictionaryClassifier).reloadCuratedEntries();
    }

    @Test
    void deleteLearned_deletesAndRebuildsSnapshot() {
        var id = UUID.randomUUID();
        when(learnedRepository.findAll()).thenReturn(List.of());

        service.deleteLearned(id);

        verify(learnedRepository).deleteById(id);
        verify(dictionaryClassifier).replaceLearnedEntries(eq(Map.of()));
    }
}
