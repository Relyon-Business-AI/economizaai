package com.relyon.economizaai.service.extraction.ml;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MlClassifierServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private MlClassifierService service;

    @BeforeEach
    void seedConfig() {
        ReflectionTestUtils.setField(service, "confidenceThreshold", 0.75);
        ReflectionTestUtils.setField(service, "categoryApplyEnabled", false);
    }

    private Product product(String normalizedName, ProductCategory category,
                            String genericName, CategorizationSource source) {
        return Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(normalizedName)
                .category(category)
                .genericName(genericName)
                .categorizationSource(source)
                .build();
    }

    private List<Product> trustedTrainingSet() {
        var products = new ArrayList<Product>();
        for (var index = 0; index < 20; index++) {
            products.add(product("arroz tio joao 5kg " + index, ProductCategory.GROCERIES,
                    "Arroz", CategorizationSource.DICTIONARY));
            products.add(product("limpador veja limao 500ml " + index, ProductCategory.CLEANING,
                    "Limpador", CategorizationSource.USER));
        }
        return products;
    }

    @Test
    void retrain_trainsWhenEnoughTrustedExamples() {
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(trustedTrainingSet());

        var outcome = service.retrain();

        assertTrue(outcome.trained());
        assertEquals(40, outcome.categoryExamples());
        assertEquals(40, outcome.genericNameExamples());
        assertNotNull(outcome.elapsed());
        assertTrue(service.isReady());
        assertNotNull(service.getLastTrainedAt());
    }

    @Test
    void retrain_skipsAndStaysNotReadyWhenInsufficientData() {
        var tiny = List.of(
                product("arroz", ProductCategory.GROCERIES, "Arroz", CategorizationSource.DICTIONARY),
                product("leite", ProductCategory.MEAT_DAIRY, "Leite", CategorizationSource.USER)
        );
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(tiny);

        var outcome = service.retrain();

        assertFalse(outcome.trained());
        assertEquals(2, outcome.categoryExamples());
        assertFalse(service.isReady());
        assertNull(service.getLastTrainedAt());
    }

    @Test
    void retrain_filtersOutNonTrustedSources() {
        // Repository is queried only for TRUSTED_SOURCES; ML products never come back.
        // Simulate the real repo returning only the 2 trusted products that pass the filter.
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(List.of(
                product("arroz", ProductCategory.GROCERIES, "Arroz", CategorizationSource.DICTIONARY),
                product("leite", ProductCategory.MEAT_DAIRY, "Leite", CategorizationSource.USER)
        ));

        var outcome = service.retrain();

        assertFalse(outcome.trained());
        assertEquals(2, outcome.categoryExamples());
        assertFalse(service.isReady());
    }

    @Test
    void retrain_excludesLearnedDictionaryFromTrustedSources() {
        // LEARNED_DICTIONARY is ML-once-removed; including it would create a feedback loop.
        // Verify the repo is queried with DICTIONARY + USER only.
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(List.of());

        service.retrain();

        @SuppressWarnings("unchecked")
        var captor = (ArgumentCaptor<Collection<CategorizationSource>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Collection.class);
        verify(productRepository).findByCategorizationSourceIn(captor.capture());
        var sources = captor.getValue();
        assertTrue(sources.contains(CategorizationSource.DICTIONARY));
        assertTrue(sources.contains(CategorizationSource.USER));
        assertTrue(sources.contains(CategorizationSource.CONSENSUS));
        assertFalse(sources.contains(CategorizationSource.LEARNED_DICTIONARY),
                "LEARNED_DICTIONARY excluded to prevent ML→learned_dict→ML feedback loop");
        assertFalse(sources.contains(CategorizationSource.ML));
    }

    @Test
    void retrain_ignoresProductsWithNullCategoryForCategoryModel() {
        var products = new ArrayList<Product>();
        for (var index = 0; index < 35; index++) {
            products.add(product("arroz " + index, ProductCategory.GROCERIES,
                    "Arroz", CategorizationSource.DICTIONARY));
        }
        for (var index = 0; index < 10; index++) {
            products.add(product("sem categoria " + index, null,
                    "Misterio", CategorizationSource.USER));
        }
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(products);

        var outcome = service.retrain();

        assertTrue(outcome.trained());
        assertEquals(35, outcome.categoryExamples());
        assertEquals(45, outcome.genericNameExamples());
    }

    @Test
    void predictCategory_returnsEmptyWhenNotReady() {
        var prediction = service.predictCategory("arroz tio joao");

        assertNull(prediction.label());
        assertEquals(0.0, prediction.confidence());
    }

    @Test
    void predictGenericName_returnsEmptyWhenNotReady() {
        var prediction = service.predictGenericName("arroz tio joao");

        assertNull(prediction.label());
        assertEquals(0.0, prediction.confidence());
    }

    @Test
    void predictCategory_returnsTrainedLabelWhenReady() {
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(trustedTrainingSet());
        service.retrain();

        var prediction = service.predictCategory("arroz jasmim 5kg");

        assertEquals(ProductCategory.GROCERIES, prediction.label());
        assertTrue(prediction.confidence() > 0.0);
    }

    @Test
    void predictGenericName_returnsTrainedLabelWhenReady() {
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(trustedTrainingSet());
        service.retrain();

        var prediction = service.predictGenericName("limpador multiuso 1l");

        assertEquals("Limpador", prediction.label());
    }

    @Test
    void isCategoryApplyEnabled_reflectsConfiguredValue() {
        assertFalse(service.isCategoryApplyEnabled());
        ReflectionTestUtils.setField(service, "categoryApplyEnabled", true);
        assertTrue(service.isCategoryApplyEnabled());
    }

    @Test
    void getConfidenceThreshold_reflectsConfiguredValue() {
        assertEquals(0.75, service.getConfidenceThreshold());
        ReflectionTestUtils.setField(service, "confidenceThreshold", 0.9);
        assertEquals(0.9, service.getConfidenceThreshold());
    }

    @Test
    void retrainBecomesNotReadyAgainWhenDataDropsBelowMinimum() {
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(trustedTrainingSet());
        service.retrain();
        assertTrue(service.isReady());

        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(List.of(
                product("arroz", ProductCategory.GROCERIES, "Arroz", CategorizationSource.DICTIONARY)
        ));
        var outcome = service.retrain();

        assertFalse(outcome.trained());
        assertFalse(service.isReady());
    }

    @Test
    void scheduledRetrainDelegatesToRetrain() {
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(trustedTrainingSet());

        service.scheduledRetrain();

        assertTrue(service.isReady());
    }
}
