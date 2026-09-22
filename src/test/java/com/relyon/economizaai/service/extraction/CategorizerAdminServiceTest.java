package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.EanCatalogRepository;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
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
