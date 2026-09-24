package com.relyon.economizaai.service;

import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.extraction.BrandExtractor;
import com.relyon.economizaai.service.extraction.DictionaryClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NormalizationBackfillRunnerTest {

    @Mock private CuratedDictionaryEntryRepository curatedRepository;
    @Mock private LearnedDictionaryRepository learnedRepository;
    @Mock private BrandRegistryEntryRepository brandRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;
    @Mock private BrandExtractor brandExtractor;

    @InjectMocks private NormalizationBackfillRunner runner;

    @Test
    void backfillCuratedKeys_reNormalizesAccentedKeyWhenNoCollision() {
        var entry = CuratedDictionaryEntry.builder().id(UUID.randomUUID()).keyword("café").build();
        when(curatedRepository.findAll()).thenReturn(List.of(entry));
        when(curatedRepository.findByKeyword("cafe")).thenReturn(Optional.empty());

        var updated = runner.backfillCuratedKeys();

        assertEquals(1, updated);
        assertEquals("cafe", entry.getKeyword(), "accented key re-normalized in place");
        verify(curatedRepository).save(entry);
    }

    @Test
    void backfillCuratedKeys_collisionSkipsNonDestructively() {
        var accented = CuratedDictionaryEntry.builder().id(UUID.randomUUID()).keyword("açúcar").build();
        when(curatedRepository.findAll()).thenReturn(List.of(accented));
        // Another row already holds the normalized key → collision.
        when(curatedRepository.findByKeyword("acucar"))
                .thenReturn(Optional.of(CuratedDictionaryEntry.builder().id(UUID.randomUUID()).keyword("acucar").build()));

        var updated = runner.backfillCuratedKeys();

        assertEquals(0, updated);
        assertEquals("açúcar", accented.getKeyword(), "left untouched on collision");
        verify(curatedRepository, never()).save(any());
        verify(curatedRepository, never()).deleteById(any());
    }

    @Test
    void backfillProductNorms_fillsNullMirrorsThenIsIdempotent() {
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("Nescafe").genericName("Nescafé").brand(null).build();
        // First pass returns the product needing norms; second pass finds none (idempotent).
        when(productRepository.findNeedingNormBackfill())
                .thenReturn(List.of(product))
                .thenReturn(List.of());

        runner.backfillProductNorms();
        assertEquals("nescafe", product.getGenericNameNorm(), "generic mirror filled");
        assertNull(product.getBrandNorm(), "null brand stays null (normalizeOrNull)");

        runner.backfillProductNorms();
        verify(productRepository, times(1)).saveAll(any());
    }

    @Test
    void run_neverThrowsWhenARepositoryFails() {
        when(curatedRepository.findAll()).thenThrow(new RuntimeException("db down"));
        when(learnedRepository.findAll()).thenReturn(List.of());
        when(brandRepository.findAll()).thenReturn(List.of());
        when(productRepository.findNeedingNormBackfill()).thenReturn(List.of());
        when(aliasRepository.findAll()).thenReturn(List.of());

        assertDoesNotThrow(() -> runner.run(null));
        // No successful curated update → no snapshot reload triggered.
        verify(dictionaryClassifier, never()).reloadCuratedEntries();
    }

    @Test
    void backfillAliasKeys_reNormalizesFromRawDescription() {
        // Stored key predates the digit-split; re-normalizing from RAW must fix it.
        var alias = ProductAlias.builder().id(UUID.randomUUID())
                .rawDescription("SHAMP PALMOLIVE DETOX350ML")
                .normalizedDescription("shamp palmolive detox350ml")
                .build();
        when(aliasRepository.findAll()).thenReturn(List.of(alias));
        when(aliasRepository.existsByNormalizedDescription("shamp palmolive detox 350 ml")).thenReturn(false);

        var updated = runner.backfillAliasKeys();

        assertEquals(1, updated);
        assertEquals("shamp palmolive detox 350 ml", alias.getNormalizedDescription());
        verify(aliasRepository).save(alias);
    }

    @Test
    void backfillAliasKeys_collisionLeavesRowUntouched() {
        var alias = ProductAlias.builder().id(UUID.randomUUID())
                .rawDescription("LEITE 1L")
                .normalizedDescription("leite 1l")
                .build();
        when(aliasRepository.findAll()).thenReturn(List.of(alias));
        when(aliasRepository.existsByNormalizedDescription("leite 1 l")).thenReturn(true);

        var updated = runner.backfillAliasKeys();

        assertEquals(0, updated);
        assertEquals("leite 1l", alias.getNormalizedDescription(), "left untouched on collision");
        verify(aliasRepository, never()).save(any());
    }
}
