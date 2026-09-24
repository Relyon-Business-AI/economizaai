package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import com.relyon.economizaai.model.LearnedDictionaryEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ConsensusGraduationAuditRepository;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage beyond {@link ConsensusPromotionServiceTest}: null-category
 * overrides ignored, already-graduated products not re-saved, missing products
 * skipped, token disagreement / under-threshold skips, the upsert-existing
 * learned-entry path, the learned-dictionary reload, and scheduledPromote.
 */
@ExtendWith(MockitoExtension.class)
class ConsensusPromotionServiceCoverageTest {

    @Mock private HouseholdProductCategoryOverrideRepository overrideRepository;
    @Mock private ProductRepository productRepository;
    @Mock private LearnedDictionaryRepository learnedRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;
    @Mock private ConsensusGraduationAuditRepository auditRepository;

    private ConsensusPromotionService service;

    @BeforeEach
    void setUp() {
        service = new ConsensusPromotionService(overrideRepository, productRepository, learnedRepository, dictionaryClassifier, auditRepository);
        ReflectionTestUtils.setField(service, "minHouseholds", 2);
        ReflectionTestUtils.setField(service, "minTokenProducts", 2);
        lenient().when(learnedRepository.findAll()).thenReturn(List.of());
        lenient().when(learnedRepository.findByNormalizedTokenIn(any())).thenReturn(List.of());
    }

    private HouseholdProductCategoryOverride override(Product product, ProductCategory category) {
        return HouseholdProductCategoryOverride.builder()
                .household(Household.builder().id(UUID.randomUUID()).build())
                .product(product).category(category).build();
    }

    private Product product(String name, ProductCategory category, CategorizationSource source) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name)
                .category(category).categorizationSource(source).build();
    }

    @Test
    void nullCategoryOverrides_areIgnored() {
        var milho = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, null), override(milho, null)));

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated());
        verify(productRepository).findAllById(Set.of());
        verify(productRepository, never()).saveAll(any());
    }

    @Test
    void productAlreadyGraduatedToConsensusCategory_isNotResaved() {
        var milho = product("MILHO ODERICH", ProductCategory.GROCERIES, CategorizationSource.CONSENSUS);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, ProductCategory.GROCERIES),
                override(milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of(milho));

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated(), "no-op when already graduated identically");
        verify(productRepository, never()).saveAll(any());
    }

    @Test
    void missingProduct_isSkipped() {
        var milho = product("MILHO", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, ProductCategory.GROCERIES),
                override(milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of());

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated());
        verify(productRepository, never()).saveAll(any());
    }

    @Test
    void tokenDisagreementAcrossProducts_isNotLearned() {
        var milhoA = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        var milhoB = product("MILHO VERDE PREDILECTA 170G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milhoA, ProductCategory.GROCERIES),
                override(milhoA, ProductCategory.GROCERIES),
                override(milhoB, ProductCategory.PRODUCE),
                override(milhoB, ProductCategory.PRODUCE)));
        when(productRepository.findAllById(Set.of(milhoA.getId(), milhoB.getId())))
                .thenReturn(List.of(milhoA, milhoB));

        var outcome = service.promote();

        assertEquals(2, outcome.productsGraduated());
        assertEquals(0, outcome.tokensLearned(), "conflicting categories block the shared token");
    }

    @Test
    void tokenBelowMinTokenProducts_isNotLearned() {
        var milho = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, ProductCategory.GROCERIES),
                override(milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of(milho));

        var outcome = service.promote();

        assertEquals(1, outcome.productsGraduated());
        assertEquals(0, outcome.tokensLearned());
        verify(learnedRepository, never()).saveAll(anyCollection());
    }

    @Test
    void existingLearnedEntry_isUpdatedInPlace() {
        var milhoA = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        var milhoB = product("MILHO VERDE 170G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milhoA, ProductCategory.GROCERIES),
                override(milhoA, ProductCategory.GROCERIES),
                override(milhoB, ProductCategory.GROCERIES),
                override(milhoB, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milhoA.getId(), milhoB.getId())))
                .thenReturn(List.of(milhoA, milhoB));
        var existing = LearnedDictionaryEntry.builder()
                .normalizedToken("milho").category(ProductCategory.OTHER)
                .sampleCount(0).promotedAt(LocalDateTime.now().minusDays(5)).build();
        // Return the existing entry when the batch lookup for tokens includes "milho"
        when(learnedRepository.findByNormalizedTokenIn(anyCollection())).thenReturn(List.of(existing));

        var outcome = service.promote();

        assertTrue(outcome.tokensLearned() >= 1);
        // existing instance reused and re-categorized, not replaced
        assertEquals(ProductCategory.GROCERIES, existing.getCategory());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearnedDictionaryEntry>> captor = ArgumentCaptor.captor();
        verify(learnedRepository).saveAll(captor.capture());
        assertTrue(captor.getValue().contains(existing));
    }

    @Test
    void reloadLearnedEntries_pushesAllEntriesIntoClassifier() {
        var milho = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, ProductCategory.GROCERIES),
                override(milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of(milho));
        var learnedEntry = LearnedDictionaryEntry.builder()
                .normalizedToken("milho").genericName("milho").category(ProductCategory.GROCERIES)
                .sampleCount(0).promotedAt(LocalDateTime.now()).build();
        when(learnedRepository.findAll()).thenReturn(List.of(learnedEntry));

        var outcome = service.promote();

        assertEquals(1, outcome.learnedTotal());
        verify(dictionaryClassifier).replaceLearnedEntries(argThat(map -> map.containsKey("milho")));
    }

    @Test
    void scheduledPromote_delegatesToPromote() {
        when(overrideRepository.findAll()).thenReturn(List.of());

        service.scheduledPromote();

        verify(overrideRepository).findAll();
        verify(dictionaryClassifier).replaceLearnedEntries(anyMap());
    }

    @Test
    void emptyOverrides_produceZeroOutcome() {
        when(overrideRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated());
        assertEquals(0, outcome.tokensLearned());
        assertEquals(0, outcome.learnedTotal());
    }
}
