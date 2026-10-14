package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
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
    @Mock private CategoryResolver categoryResolver;
    @InjectMocks private TaxonomyRecategorizationService service;

    @Test
    void recategorize_runsNameThenOffCatalog_inOrder() {
        when(productRepository.findAll()).thenReturn(List.of()); // name pass: nothing to update
        when(productRepository.findDistinctEans()).thenReturn(List.of("111", "222"));
        when(enrichmentService.enrichForce(List.of("111", "222"))).thenReturn(2);
        when(productRepository.applyCatalogCategoryNodes()).thenReturn(5);

        var result = service.recategorizeFromEanCatalog();

        assertEquals(0, result.nameUpdated());
        assertEquals(2, result.eansScanned());
        assertEquals(2, result.catalogRowsEnriched());
        assertEquals(5, result.productsUpdated());
        // Name pass first, then OFF re-fetch, then apply catalog nodes.
        var order = inOrder(productRepository, enrichmentService);
        order.verify(productRepository).findAll();
        order.verify(enrichmentService).enrichForce(List.of("111", "222"));
        order.verify(productRepository).applyCatalogCategoryNodes();
    }
}
