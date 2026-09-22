package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.LearnedDictionaryEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoPromotionServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private LearnedDictionaryRepository learnedRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;

    @InjectMocks private AutoPromotionService service;

    @BeforeEach
    void seedThresholds() {
        ReflectionTestUtils.setField(service, "minSamples", 30);
        ReflectionTestUtils.setField(service, "minAgreement", 0.90);
    }

    private Product mlProduct(String name, ProductCategory cat, String genericName) {
        return Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(name)
                .category(cat)
                .genericName(genericName)
                .categorizationSource(CategorizationSource.ML)
                .build();
    }

    private Product userProduct(String name, ProductCategory cat) {
        return Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(name)
                .category(cat)
                .categorizationSource(CategorizationSource.USER)
                .build();
    }

    @Test
    void promotesTokenWith30AgreeingMlSamplesAndNoUserOverride() {
        var products = IntStream.range(0, 30)
                .mapToObj(i -> mlProduct("RACAO PEDIGREE FILHOTE " + i, ProductCategory.OTHER, "Ração"))
                .toList();
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(products);
        when(learnedRepository.findByNormalizedTokenIn(any())).thenReturn(List.of());
        when(learnedRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        assertTrue(outcome.promoted() > 0,
                "racao, pedigree, filhote and N-gram phrases all reach 30 samples");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearnedDictionaryEntry>> captor = ArgumentCaptor.captor();
        verify(learnedRepository).saveAll(captor.capture());
        assertEquals(outcome.promoted(), captor.getValue().size());
    }

    @Test
    void userOverrideBlocksPromotionOfThatExactToken() {
        var products = new ArrayList<Product>(IntStream.range(0, 50)
                .mapToObj(i -> mlProduct("RACAO " + i, ProductCategory.OTHER, "Ração"))
                .toList());
        products.add(userProduct("RACAO", ProductCategory.GROCERIES));
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(products);
        when(learnedRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        // "racao" had a user override → not promoted. Per-number tokens don't
        // reach 30 samples individually, so nothing else qualifies either.
        assertEquals(0, outcome.promoted());
        verify(learnedRepository, never()).saveAll(any());
    }

    @Test
    void blocksPromotionWhenAgreementBelowThreshold() {
        var groceries = IntStream.range(0, 20)
                .mapToObj(i -> mlProduct("FOO " + i, ProductCategory.GROCERIES, "X"));
        var cleaning = IntStream.range(0, 15)
                .mapToObj(i -> mlProduct("FOO " + i, ProductCategory.CLEANING, "X"));
        var products = Stream.concat(groceries, cleaning).toList();
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(products);
        when(learnedRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        // 35 samples for "foo" but only 20/35 = 57% agreement → below 90% threshold
        assertEquals(0, outcome.promoted());
    }

    @Test
    void skipsTokensBelow30Samples() {
        var products = IntStream.range(0, 10)
                .mapToObj(i -> mlProduct("BAR " + i, ProductCategory.OTHER, "Y"))
                .toList();
        when(productRepository.findByCategorizationSourceIn(any())).thenReturn(products);
        when(learnedRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        assertEquals(0, outcome.promoted());
    }
}
