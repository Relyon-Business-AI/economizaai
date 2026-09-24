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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsensusPromotionServiceTest {

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

    private HouseholdProductCategoryOverride override(UUID household, Product product, ProductCategory category) {
        return HouseholdProductCategoryOverride.builder()
                .household(Household.builder().id(household).build())
                .product(product).category(category).build();
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name)
                .category(ProductCategory.OTHER).categorizationSource(CategorizationSource.ML).build();
    }

    @Test
    void singleHousehold_doesNotGraduate() {
        var milho = product("MILHO ODERICH 200G");
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES)));

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated());
        verify(productRepository, never()).saveAll(any());
    }

    @Test
    void twoHouseholdsAgree_graduatesProductGlobally() {
        var milho = product("MILHO ODERICH 200G");
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of(milho));

        var outcome = service.promote();

        assertEquals(1, outcome.productsGraduated());
        assertEquals(ProductCategory.GROCERIES, milho.getCategory());
        assertEquals(CategorizationSource.CONSENSUS, milho.getCategorizationSource());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Product>> captor = ArgumentCaptor.captor();
        verify(productRepository).saveAll(captor.capture());
        assertTrue(captor.getValue().contains(milho));
    }

    @Test
    void tie_doesNotGraduate() {
        var milho = product("MILHO ODERICH 200G");
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milho, ProductCategory.PRODUCE),
                override(UUID.randomUUID(), milho, ProductCategory.PRODUCE)));
        lenient().when(productRepository.findAllById(any())).thenReturn(List.of());

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated(), "tie is not consensus");
    }

    @Test
    void recurringTokenAcrossConsensusProducts_isLearned() {
        var milhoA = product("MILHO ODERICH 200G");
        var milhoB = product("MILHO VERDE PREDILECTA 170G");
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(UUID.randomUUID(), milhoA, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milhoA, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milhoB, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milhoB, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milhoA.getId(), milhoB.getId())))
                .thenReturn(List.of(milhoA, milhoB));

        var outcome = service.promote();

        assertEquals(2, outcome.productsGraduated());
        assertTrue(outcome.tokensLearned() > 0, "shared token 'milho' appears in both products");
        // "milho" should be persisted with category GROCERIES
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearnedDictionaryEntry>> captor = ArgumentCaptor.captor();
        verify(learnedRepository, atLeastOnce()).saveAll(captor.capture());
        var savedMilho = captor.getAllValues().stream()
                .flatMap(List::stream)
                .anyMatch(e -> "milho".equals(e.getNormalizedToken()) && e.getCategory() == ProductCategory.GROCERIES);
        assertTrue(savedMilho);
    }
}
