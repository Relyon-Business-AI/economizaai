package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrandAliasPromotionServiceTest {

    private ProductAliasRepository aliasRepository;
    private BrandRegistryEntryRepository brandRepository;
    private BrandAliasPromotionService service;

    @BeforeEach
    void setUp() {
        aliasRepository = mock(ProductAliasRepository.class);
        brandRepository = mock(BrandRegistryEntryRepository.class);
        // Registry knows the full brand only ("dona benta"), not the abbreviation.
        when(brandRepository.findAll()).thenReturn(List.of(
                BrandRegistryEntry.builder().normalizedKey("dona benta").displayName("Dona Benta").build()));
        var brandExtractor = new BrandExtractor(brandRepository);
        brandExtractor.reload();
        service = new BrandAliasPromotionService(aliasRepository, brandRepository, brandExtractor);
    }

    @Test
    void promotesAbbreviationToAlias() {
        when(aliasRepository.findRawDescriptionsOfBrandedProducts()).thenReturn(List.<Object[]>of(
                new Object[]{"ferm bio d benta 10g", "Dona Benta"}));
        when(brandRepository.findByNormalizedKey("d benta")).thenReturn(Optional.empty());

        var outcome = service.promoteFromKnownBrands();

        assertEquals(1, outcome.created());
        var captor = ArgumentCaptor.forClass(List.class);
        verify(brandRepository).saveAll(captor.capture());
        var saved = (List<BrandRegistryEntry>) captor.getValue();
        assertEquals("d benta", saved.get(0).getNormalizedKey());
        assertEquals("Dona Benta", saved.get(0).getDisplayName());
        assertEquals("LEARNED_ALIAS", saved.get(0).getSource());
    }

    @Test
    void skipsWhenAliasAlreadyExists() {
        when(aliasRepository.findRawDescriptionsOfBrandedProducts()).thenReturn(List.<Object[]>of(
                new Object[]{"ferm bio d benta 10g", "Dona Benta"}));
        when(brandRepository.findByNormalizedKey("d benta"))
                .thenReturn(Optional.of(BrandRegistryEntry.builder()
                        .normalizedKey("d benta").displayName("Dona Benta").build()));

        var outcome = service.promoteFromKnownBrands();

        assertEquals(0, outcome.created());
        verify(brandRepository, never()).saveAll(anyList());
    }

    @Test
    void dropsConflictingKeys() {
        // The SAME abbreviated key "dona b" would map to two different brands →
        // ambiguous, so it's dropped instead of guessing.
        when(aliasRepository.findRawDescriptionsOfBrandedProducts()).thenReturn(List.<Object[]>of(
                new Object[]{"farinha dona b especial", "Dona Benta"},
                new Object[]{"vinho dona b tinto", "Dona Bianco"}));
        when(brandRepository.findByNormalizedKey("dona b")).thenReturn(Optional.empty());

        var outcome = service.promoteFromKnownBrands();

        assertEquals(0, outcome.created());
        assertEquals(1, outcome.conflictsSkipped());
        verify(brandRepository, never()).saveAll(anyList());
    }

    @Test
    void promoteForProduct_learnsFromDescriptions() {
        var learned = service.promoteForProduct("Dona Benta", List.of("ferm bio d benta 10g"));
        assertEquals(1, learned);
    }

    @Test
    void promoteForProduct_blankBrandIsNoOp() {
        assertEquals(0, service.promoteForProduct("  ", List.of("ferm bio d benta 10g")));
        verify(brandRepository, never()).saveAll(anyList());
    }
}
