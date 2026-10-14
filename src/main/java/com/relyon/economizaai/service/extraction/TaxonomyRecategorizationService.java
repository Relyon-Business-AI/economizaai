package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

/**
 * Re-categorizes existing products into the new two-level taxonomy node
 * ({@code products.category_id}). Two complementary signals, neither on the
 * ingest hot path:
 * <ol>
 *   <li><b>Name classifier (primary):</b> {@link ProductNameLeafClassifier} maps
 *       the product name to a fine leaf — the reliable signal for Brazilian goods
 *       (OFF barely covers their EANs).</li>
 *   <li><b>OFF catalog (secondary):</b> force re-fetch OFF for product EANs so
 *       catalog rows get the fine node, then apply it where present.</li>
 * </ol>
 * Products neither signal places keep the group-level node from the V100 backfill.
 * Admin-triggered — see CategorizerController.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxonomyRecategorizationService {

    private final ProductRepository productRepository;
    private final EanCatalogEnrichmentService enrichmentService;
    private final CategoryResolver categoryResolver;

    public RecategorizeResult recategorizeFromEanCatalog() {
        var byName = recategorizeByName();
        var eans = productRepository.findDistinctEans();
        var enriched = enrichmentService.enrichForce(eans);        // OFF re-fetch (untransacted)
        var byCatalog = productRepository.applyCatalogCategoryNodes();
        log.info("taxonomy.recategorize name_updated={} eans={} enriched={} catalog_updated={}",
                byName, eans.size(), enriched, byCatalog);
        return new RecategorizeResult(byName, eans.size(), enriched, byCatalog);
    }

    /** Set products' category_id from the name-based leaf classifier where confident. */
    @Transactional
    public int recategorizeByName() {
        var updated = 0;
        var toSave = new ArrayList<Product>();
        for (var product : productRepository.findAll()) {
            var slug = ProductNameLeafClassifier.classify(product.getNormalizedName());
            if (slug == null) continue;
            var nodeId = categoryResolver.idForSlug(slug);
            if (nodeId != null && !nodeId.equals(product.getCategoryId())) {
                product.setCategoryId(nodeId);
                toSave.add(product);
                updated++;
            }
        }
        productRepository.saveAll(toSave);
        return updated;
    }

    public record RecategorizeResult(int nameUpdated, int eansScanned, int catalogRowsEnriched, int productsUpdated) {}
}
