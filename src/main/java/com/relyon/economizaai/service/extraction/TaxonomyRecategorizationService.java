package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Re-categorizes existing products into the new two-level taxonomy node
 * ({@code products.category_id}) using the EAN catalog's fine node. Two steps,
 * neither on the ingest hot path:
 * <ol>
 *   <li>Re-fetch OFF for every product EAN (force) so catalog rows get the fine
 *       node + extra metadata that older imports lacked. HTTP work, untransacted.</li>
 *   <li>Bulk-apply {@code ean_catalog.category_id} → {@code products.category_id}
 *       in one UPDATE.</li>
 * </ol>
 * Products without an EAN (or with no OFF hit) keep the group-level node seeded
 * by the V100 backfill. Admin-triggered — see CategorizerController.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxonomyRecategorizationService {

    private final ProductRepository productRepository;
    private final EanCatalogEnrichmentService enrichmentService;

    public RecategorizeResult recategorizeFromEanCatalog() {
        var eans = productRepository.findDistinctEans();
        var enriched = enrichmentService.enrichForce(eans); // OFF re-fetch (untransacted)
        var updated = productRepository.applyCatalogCategoryNodes();
        log.info("taxonomy.recategorize eans={} enriched={} products_updated={}",
                eans.size(), enriched, updated);
        return new RecategorizeResult(eans.size(), enriched, updated);
    }

    public record RecategorizeResult(int eansScanned, int catalogRowsEnriched, int productsUpdated) {}
}
