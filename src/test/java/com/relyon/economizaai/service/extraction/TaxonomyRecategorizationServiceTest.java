package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxonomyRecategorizationServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private EanCatalogEnrichmentService enrichmentService;
    @InjectMocks private TaxonomyRecategorizationService service;

    @Test
    void recategorize_refetchesThenAppliesNodes_inOrder() {
        when(productRepository.findDistinctEans()).thenReturn(List.of("111", "222"));
        when(enrichmentService.enrichForce(List.of("111", "222"))).thenReturn(2);
        when(productRepository.applyCatalogCategoryNodes()).thenReturn(5);

        var result = service.recategorizeFromEanCatalog();

        assertEquals(2, result.eansScanned());
        assertEquals(2, result.catalogRowsEnriched());
        assertEquals(5, result.productsUpdated());
        // OFF re-fetch must run BEFORE applying catalog nodes to products.
        var order = inOrder(enrichmentService, productRepository);
        order.verify(enrichmentService).enrichForce(List.of("111", "222"));
        order.verify(productRepository).applyCatalogCategoryNodes();
    }
}
