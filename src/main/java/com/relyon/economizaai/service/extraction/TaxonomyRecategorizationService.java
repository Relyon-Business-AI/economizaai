package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.extraction.ProductNameLeafClassifier.Confidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Re-categorizes products into the new two-level taxonomy node ({@code category_id}).
 * HYBRID, deterministic baseline first:
 * <ol>
 *   <li><b>Reset</b> every product to its enum group default (wipes stale nodes).</li>
 *   <li><b>Keyword (free):</b> {@link ProductNameLeafClassifier} — only HIGH-confidence
 *       results (single group + strong keyword) are trusted.</li>
 *   <li><b>LLM (Claude):</b> everything the keyword marked LOW/NONE goes to
 *       {@link AiFineCategoryClassifier} in batches — shares the AI daily cap + ledger.</li>
 *   <li><b>OFF catalog:</b> force re-fetch, apply any fine node OFF knows.</li>
 * </ol>
 * Non-food enums (CLEANING/PERSONAL_CARE/HEALTH/PET) keep their group node. Admin-triggered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxonomyRecategorizationService {

    private static final int LLM_BATCH = 40;

    private final ProductRepository productRepository;
    private final EanCatalogEnrichmentService enrichmentService;
    private final CategoryResolver categoryResolver;
    private final AiFineCategoryClassifier aiFineCategoryClassifier;

    public RecategorizeResult recategorizeFromEanCatalog() {
        productRepository.resetCategoryIdFromEnumGroup();
        productRepository.clearCategoryIdForNullEnum();

        var food = productRepository.findAll().stream()
                .filter(product -> !isNonFood(product.getCategory()))
                .toList();

        var byKeyword = applyKeywordHighConfidence(food);  // HIGH only; returns LOW/NONE pending
        var byLlm = applyLlmForLowConfidence(byKeyword.pending());

        var eans = productRepository.findDistinctEans();
        var enriched = enrichmentService.enrichForce(eans);
        var byCatalog = productRepository.applyCatalogCategoryNodes();

        log.info("taxonomy.recategorize keyword={} llm={} pending={} eans={} enriched={} catalog={}",
                byKeyword.updated(), byLlm, byKeyword.pending().size(), eans.size(), enriched, byCatalog);
        return new RecategorizeResult(byKeyword.updated(), byLlm, eans.size(), enriched, byCatalog);
    }

    /**
     * Lightweight pass for the scheduler: only products that still LACK a fine node
     * (category_id null), not USER-locked, food. Keyword HIGH + LLM for the rest. Does
     * NOT reset existing nodes and does NOT re-fetch Open Food Facts — cheap enough to
     * run daily (unlike {@link #recategorizeFromEanCatalog()}, which hits OFF per EAN).
     */
    public RecategorizeResult recategorizeIncremental() {
        var pending = productRepository.findByCategoryIdIsNull().stream()
                .filter(product -> product.getCategorizationSource() != CategorizationSource.USER)
                .filter(product -> !isNonFood(product.getCategory()))
                .toList();
        if (pending.isEmpty()) return new RecategorizeResult(0, 0, 0, 0, 0);
        var byKeyword = applyKeywordHighConfidence(pending);
        var byLlm = applyLlmForLowConfidence(byKeyword.pending());
        log.info("taxonomy.recategorize.incremental keyword={} llm={} candidates={}",
                byKeyword.updated(), byLlm, pending.size());
        return new RecategorizeResult(byKeyword.updated(), byLlm, 0, 0, 0);
    }

    private record KeywordPass(int updated, List<Product> pending) {}

    private KeywordPass applyKeywordHighConfidence(List<Product> food) {
        var toSave = new ArrayList<Product>();
        var pending = new ArrayList<Product>();
        for (var product : food) {
            var result = ProductNameLeafClassifier.classify(product.getNormalizedName());
            if (result.confidence() == Confidence.HIGH) {
                var nodeId = categoryResolver.idForSlug(result.slug());
                if (nodeId != null && !nodeId.equals(product.getCategoryId())) {
                    product.setCategoryId(nodeId);
                    toSave.add(product);
                }
            } else {
                pending.add(product); // LOW (conflict / weak-only) or NONE → LLM decides
            }
        }
        productRepository.saveAll(toSave);
        return new KeywordPass(toSave.size(), pending);
    }

    /** Batch the low-confidence products through the LLM; stop gracefully if the AI cap/credit runs out. */
    private int applyLlmForLowConfidence(List<Product> pending) {
        if (pending.isEmpty() || !aiFineCategoryClassifier.isEnabled()) return 0;
        var byId = new HashMap<UUID, Product>();
        pending.forEach(product -> byId.put(product.getId(), product));
        var updated = 0;
        for (var start = 0; start < pending.size(); start += LLM_BATCH) {
            var batch = pending.subList(start, Math.min(start + LLM_BATCH, pending.size()));
            try {
                var placed = aiFineCategoryClassifier.classify(batch);
                var toSave = new ArrayList<Product>();
                placed.forEach((productId, categoryId) -> {
                    var product = byId.get(productId);
                    if (product != null && !categoryId.equals(product.getCategoryId())) {
                        product.setCategoryId(categoryId);
                        toSave.add(product);
                    }
                });
                productRepository.saveAll(toSave);
                updated += toSave.size();
            } catch (AiGateway.AiUnavailableException unavailable) {
                log.warn("taxonomy.recategorize llm stopped reason={}", unavailable.getMessage());
                break; // cap/credit exhausted — the rest keep their group default
            }
        }
        return updated;
    }

    private static boolean isNonFood(ProductCategory category) {
        return category == ProductCategory.CLEANING || category == ProductCategory.PERSONAL_CARE
                || category == ProductCategory.HEALTH || category == ProductCategory.PET_SUPPLIES;
    }

    /**
     * Measures how trustworthy the keyword HIGH-confidence set is: on a food sample,
     * run the keyword classifier, then ask the LLM to classify the SAME HIGH items and
     * report agreement. precision = agree / (high items the LLM also placed). Writes
     * nothing — a dry run to decide the hybrid threshold before a full backlog pass.
     */
    public CalibrationResult calibrate(int sampleSize) {
        var food = productRepository.findAll().stream()
                .filter(product -> !isNonFood(product.getCategory()))
                .filter(product -> product.getNormalizedName() != null)
                .limit(sampleSize)
                .toList();
        var highItems = new ArrayList<Product>();
        var highSlug = new HashMap<UUID, String>();
        var low = 0;
        var none = 0;
        for (var product : food) {
            var result = ProductNameLeafClassifier.classify(product.getNormalizedName());
            switch (result.confidence()) {
                case HIGH -> { highItems.add(product); highSlug.put(product.getId(), result.slug()); }
                case LOW -> low++;
                case NONE -> none++;
            }
        }
        var agree = 0;
        var llmPlaced = 0;
        var disagreements = new ArrayList<String>();
        if (aiFineCategoryClassifier.isEnabled()) {
            for (var start = 0; start < highItems.size(); start += LLM_BATCH) {
                var batch = highItems.subList(start, Math.min(start + LLM_BATCH, highItems.size()));
                var placed = aiFineCategoryClassifier.classify(batch);
                for (var product : batch) {
                    var llmCategoryId = placed.get(product.getId());
                    if (llmCategoryId == null) continue; // LLM skipped — not counted either way
                    llmPlaced++;
                    var llmSlug = categoryResolver.slugForId(llmCategoryId);
                    var keywordSlug = highSlug.get(product.getId());
                    if (keywordSlug.equals(llmSlug)) {
                        agree++;
                    } else if (disagreements.size() < 25) {
                        disagreements.add(product.getNormalizedName() + " | kw=" + keywordSlug + " llm=" + llmSlug);
                    }
                }
            }
        }
        var precision = llmPlaced == 0 ? null : Math.round(1000.0 * agree / llmPlaced) / 10.0;
        return new CalibrationResult(food.size(), highItems.size(), low, none,
                llmPlaced, agree, precision, disagreements);
    }

    public record RecategorizeResult(int keywordUpdated, int llmUpdated, int eansScanned,
                                     int catalogRowsEnriched, int productsUpdated) {}

    public record CalibrationResult(int sample, int keywordHigh, int keywordLow, int keywordNone,
                                    int llmPlaced, int agree, Double precisionPct,
                                    List<String> disagreements) {}
}
