package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.MergeProductRequest;
import com.relyon.economizaai.dto.request.SetProductBrandRequest;
import com.relyon.economizaai.dto.response.DuplicateProductGroupResponse;
import com.relyon.economizaai.dto.response.MissingBrandProductResponse;
import com.relyon.economizaai.dto.response.BrandBackfillResponse;
import com.relyon.economizaai.dto.response.BrandCoverageReportResponse;
import com.relyon.economizaai.dto.response.UnmatchedReportResponse;
import com.relyon.economizaai.dto.response.ProductDeletionResponse;
import com.relyon.economizaai.dto.response.ProductMergeResultResponse;
import com.relyon.economizaai.dto.response.ProductResponse;
import com.relyon.economizaai.dto.response.RecategorizeReportResponse;
import com.relyon.economizaai.dto.response.RecategorizeResultResponse;
import com.relyon.economizaai.exception.InvalidProductDeletionException;
import com.relyon.economizaai.exception.InvalidProductMergeException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.extraction.BrandExtractor;
import com.relyon.economizaai.service.extraction.ProductExtractor;
import com.relyon.economizaai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ManualPurchaseRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ShoppingListItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin curation tools for the product catalog. Currently focused on
 * filling in missing brands (Item C.2) — surfacing products whose brand
 * extraction yielded null, with sample raw descriptions to give the
 * curator context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminProductService {

    private static final int SAMPLE_DESCRIPTION_LIMIT = 5;

    private final ProductRepository productRepository;
    private final ProductAliasRepository aliasRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final ManualPurchaseRepository manualPurchaseRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final HouseholdProductAliasRepository householdProductAliasRepository;
    private final ConsumptionSnoozeRepository consumptionSnoozeRepository;
    private final ProductExtractor productExtractor;
    private final BrandExtractor brandExtractor;

    /** Full catalog (paged) — for curating the dictionary/brands against real data. */
    @Transactional(readOnly = true)
    public Page<ProductResponse> listAll(Pageable pageable) {
        return listAll(null, null, null, pageable);
    }

    /**
     * Catalog list with optional filters for the admin ops center: {@code q} (name/EAN
     * substring), {@code category} (e.g. OTHER, to find the review queue), and
     * {@code source} (e.g. NONE/ML). Any null filter is ignored.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> listAll(String q, ProductCategory category, CategorizationSource source, Pageable pageable) {
        var query = (q == null || q.isBlank()) ? null : q.trim();
        return productRepository.findFiltered(query, category, source, pageable).map(ProductResponse::from);
    }

    /**
     * Re-run brand extraction over products that have no brand, filling it from
     * the (now larger) brand registry. Fill-only: never overwrites an existing
     * brand. Run after importing new brand-registry entries to fix already-
     * ingested products (brand is otherwise set only once, at creation).
     */
    @Transactional
    public BrandBackfillResponse backfillBrands() {
        var products = productRepository.findAll();
        var filled = 0;
        var stillMissing = 0;
        for (var product : products) {
            if (product.getBrand() != null) continue;
            var brand = brandExtractor.find(product.getNormalizedName());
            if (brand != null) {
                product.setBrand(brand);
                filled++;
            } else {
                stillMissing++;
            }
        }
        log.info("admin.product.backfill_brands total={} filled={} stillMissing={}",
                products.size(), filled, stillMissing);
        return new BrandBackfillResponse(products.size(), filled, stillMissing);
    }

    /**
     * Dry-run brand coverage: runs the registry over every product's description
     * WITHOUT persisting, reporting how many already have a brand, how many the
     * registry would fill now, and how many stay brandless. Measures how well we
     * can determine brands from the base we already have.
     */
    @Transactional(readOnly = true)
    public BrandCoverageReportResponse brandCoverageReport() {
        var products = productRepository.findAll();
        var alreadyHaveBrand = 0L;
        var wouldFillNow = 0L;
        var stillNoBrand = 0L;
        var samples = new ArrayList<BrandCoverageReportResponse.SampleFill>();
        for (var product : products) {
            if (product.getBrand() != null) {
                alreadyHaveBrand++;
                continue;
            }
            var brand = brandExtractor.find(product.getNormalizedName());
            if (brand != null) {
                wouldFillNow++;
                if (samples.size() < SAMPLE_DESCRIPTION_LIMIT) {
                    samples.add(new BrandCoverageReportResponse.SampleFill(product.getNormalizedName(), brand));
                }
            } else {
                stillNoBrand++;
            }
        }
        var total = products.size();
        var coverageAfter = total == 0 ? 0.0
                : Math.round((alreadyHaveBrand + wouldFillNow) * 1000.0 / total) / 10.0;
        log.info("admin.product.brand_coverage total={} have={} wouldFill={} stillMissing={} coverageAfterPct={}",
                total, alreadyHaveBrand, wouldFillNow, stillNoBrand, coverageAfter);
        return new BrandCoverageReportResponse(
                total, alreadyHaveBrand, wouldFillNow, stillNoBrand, coverageAfter, samples);
    }

    /**
     * Item→product matching diagnostics: the UNMATCHED rate (confirmed items
     * that never linked to a canonical product, hence out of the price index)
     * plus the most frequent orphan descriptions to guide curation.
     */
    @Transactional(readOnly = true)
    public UnmatchedReportResponse unmatchedReport(int topN) {
        var matched = receiptItemRepository.countMatched();
        var unmatched = receiptItemRepository.countUnmatched();
        var totalItems = matched + unmatched;
        var unmatchedPct = totalItems == 0 ? 0.0
                : Math.round(unmatched * 1000.0 / totalItems) / 10.0;
        var top = receiptItemRepository.topUnmatchedDescriptions(PageRequest.of(0, Math.max(1, topN))).stream()
                .map(row -> new UnmatchedReportResponse.UnmatchedDescription(
                        (String) row[0], ((Number) row[1]).longValue()))
                .toList();
        log.info("admin.product.unmatched_report matched={} unmatched={} unmatchedPct={}",
                matched, unmatched, unmatchedPct);
        return new UnmatchedReportResponse(matched, unmatched, unmatchedPct, top);
    }

    @Transactional(readOnly = true)
    public Page<MissingBrandProductResponse> listMissingBrand(Pageable pageable) {
        var page = productRepository.findMissingBrand(pageable);
        if (page.isEmpty()) return page.map(product -> MissingBrandProductResponse.from(product, List.of()));
        var productIds = page.getContent().stream().map(product -> product.getId()).toList();
        var aliasesByProduct = aliasRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(alias -> alias.getProduct().getId()));
        return page.map(product -> MissingBrandProductResponse.from(product,
                aliasesByProduct.getOrDefault(product.getId(), List.of()).stream()
                        .limit(SAMPLE_DESCRIPTION_LIMIT)
                        .map(ProductAlias::getRawDescription)
                        .toList()));
    }

    /**
     * Dry-run: re-run the extraction cascade over every product's stored
     * description and report where the freshly-computed category differs from
     * what's stored. Same extractor ingestion uses, so this previews exactly
     * what {@link #recategorizeApply()} would change. Read-only.
     */
    private static boolean isTrusted(CategorizationSource source) {
        return source == CategorizationSource.DICTIONARY || source == CategorizationSource.LEARNED_DICTIONARY;
    }

    @Transactional(readOnly = true)
    public RecategorizeReportResponse recategorizeReport() {
        var products = productRepository.findAll();
        var mismatches = new ArrayList<RecategorizeReportResponse.Row>();
        var applicableFromDictionary = 0;
        var mlSuggestions = 0;
        var skippedUser = 0;
        for (var product : products) {
            var suggested = productExtractor.extract(product.getNormalizedName());
            var suggestedCategory = suggested.category();
            if (suggestedCategory == null || suggestedCategory == product.getCategory()) continue;
            // USER + MERCHANT are locked against dictionary recategorization (see apply()).
            var userOverride = product.getCategorizationSource() == CategorizationSource.USER
                    || product.getCategorizationSource() == CategorizationSource.MERCHANT;
            if (userOverride) {
                skippedUser++;
            } else if (isTrusted(suggested.categorizationSource())) {
                applicableFromDictionary++;
            } else {
                mlSuggestions++;
            }
            mismatches.add(new RecategorizeReportResponse.Row(
                    product.getId(), product.getNormalizedName(), product.getEan(),
                    product.getCategory(), product.getCategorizationSource(),
                    suggestedCategory, suggested.categorizationSource(),
                    userOverride));
        }
        log.info("admin.product.recategorize.report total={} mismatches={} dict={} ml={} skippedUser={}",
                products.size(), mismatches.size(), applicableFromDictionary, mlSuggestions, skippedUser);
        return new RecategorizeReportResponse(products.size(), mismatches.size(),
                applicableFromDictionary, mlSuggestions, skippedUser, mismatches);
    }

    /**
     * Apply re-categorization. By default only **trusted** suggestions
     * (DICTIONARY / LEARNED_DICTIONARY) are applied — the ML layer is currently
     * unreliable, so its suggestions are reported but skipped unless
     * {@code includeMl} is true. Never clobbers a manual category (source=USER)
     * and never downgrades to no-category (null suggestion).
     */
    @Transactional
    public RecategorizeResultResponse recategorizeApply(boolean includeMl) {
        var products = productRepository.findAll();
        var updated = 0;
        var skippedUser = 0;
        var skippedMl = 0;
        var unchanged = 0;
        for (var product : products) {
            var suggested = productExtractor.extract(product.getNormalizedName());
            var suggestedCategory = suggested.category();
            if (suggestedCategory == null || suggestedCategory == product.getCategory()) {
                unchanged++;
                continue;
            }
            // USER (manual) and MERCHANT (pharmacy-merchant inference) are locked:
            // the dictionary cascade here has no merchant context and would wrongly
            // downgrade a pharmacy-derived category back to OTHER.
            if (product.getCategorizationSource() == CategorizationSource.USER
                    || product.getCategorizationSource() == CategorizationSource.MERCHANT) {
                skippedUser++;
                continue;
            }
            if (!isTrusted(suggested.categorizationSource()) && !includeMl) {
                skippedMl++;
                continue;
            }
            product.setCategory(suggestedCategory);
            product.setCategorizationSource(suggested.categorizationSource());
            updated++;
        }
        log.info("admin.product.recategorize.applied total={} updated={} skippedUser={} skippedMl={} unchanged={} includeMl={}",
                products.size(), updated, skippedUser, skippedMl, unchanged, includeMl);
        return new RecategorizeResultResponse(products.size(), updated, skippedUser, skippedMl, unchanged);
    }

    /**
     * Deletes a product and all its household-scoped / collaborative
     * dependents (aliases, price observations, category overrides, price
     * alerts, snoozes, shopping-list items — all {@code ON DELETE CASCADE}).
     * Receipt items that referenced it are detached ({@code product_id} set
     * to null) so confirmed purchase history is preserved as unmatched rows.
     *
     * <p>Guarded: refuses when the product still backs confirmed purchases
     * unless {@code force=true}, so we don't silently strip a real product
     * off historical receipts. Intended for pruning test/junk catalog rows.
     */
    @Transactional
    public ProductDeletionResponse delete(UUID productId, boolean force) {
        var product = productRepository.findById(productId)
                .orElseThrow(ProductNotFoundException::new);
        var receiptItemCount = receiptItemRepository.countByProduct(product);
        if (receiptItemCount > 0 && !force) {
            throw new InvalidProductDeletionException(receiptItemCount);
        }
        productRepository.delete(product);
        log.info("admin.product.deleted product={} receiptItemsDetached={} forced={}",
                productId, receiptItemCount, force);
        return new ProductDeletionResponse(productId, receiptItemCount);
    }

    @Transactional
    public ProductResponse setBrand(UUID productId, SetProductBrandRequest request) {
        var product = productRepository.findById(productId)
                .orElseThrow(ProductNotFoundException::new);
        var brand = request.brand().trim();
        product.setBrand(brand);
        var saved = productRepository.save(product);
        log.info("admin.product.brand_set product={} brand='{}'", productId, brand);
        return ProductResponse.from(saved);
    }

    /**
     * Set a canonical product's GLOBAL category manually and LOCK it (source=USER):
     * the recategorize cascade and LLM enrichment both skip USER-sourced products,
     * so a curator's decision sticks. This is the global counterpart to a household
     * category override — it changes what EVERY household sees for this product.
     */
    @Transactional
    public ProductResponse setCategory(UUID productId, ProductCategory category) {
        var product = productRepository.findById(productId)
                .orElseThrow(ProductNotFoundException::new);
        product.setCategory(category);
        product.setCategorizationSource(CategorizationSource.USER);
        var saved = productRepository.save(product);
        log.info("admin.product.category_set product={} category={} source=USER", productId, category);
        return ProductResponse.from(saved);
    }

    /**
     * Returns groups of products that share an exact `(genericName, brand,
     * packSize, packUnit)` profile — likely duplicates the curator may want
     * to merge. Only products with all four metadata dimensions populated
     * are eligible (others can't be reliably grouped). Order within a group
     * is by creation time, so the oldest product (the one that probably
     * has the most history) appears first — a natural default survivor.
     */
    @Transactional(readOnly = true)
    public List<DuplicateProductGroupResponse> listDuplicateGroups() {
        var products = productRepository.findDuplicateCandidates();
        var groups = new LinkedHashMap<String, List<Product>>();
        for (var product : products) {
            var key = product.getGenericName() + " " + product.getBrand() + " "
                    + product.getPackSize() + " " + product.getPackUnit();
            groups.computeIfAbsent(key, groupKey -> new ArrayList<>()).add(product);
        }
        var result = new ArrayList<DuplicateProductGroupResponse>(groups.size());
        for (var entry : groups.values()) {
            var head = entry.get(0);
            result.add(new DuplicateProductGroupResponse(
                    head.getGenericName(), head.getBrand(), head.getPackSize(), head.getPackUnit(),
                    head.getCategory(),
                    entry.stream().map(ProductResponse::from).toList()));
        }
        return result;
    }

    /**
     * Merges {@code absorbed} into {@code survivor}: aliases, receipt items,
     * price observations, manual purchases and shopping-list items are
     * repointed; household-scoped tables (household aliases, consumption
     * snoozes) drop absorbed's row when the survivor already has one for
     * that household — otherwise UNIQUE (household_id, product_id) would
     * fail.
     *
     * <p>Runs entirely inside one transaction. The {@code dryRun} flag
     * computes the migration counts without applying — useful for the
     * curator to sanity-check before committing. NO undo: once applied the
     * absorbed row is gone, so the dry run is the only safety net.
     */
    @Transactional
    public ProductMergeResultResponse merge(UUID survivorId, MergeProductRequest request) {
        var dryRun = Boolean.TRUE.equals(request.dryRun());
        if (Objects.equals(survivorId, request.absorbedId())) {
            throw new InvalidProductMergeException("survivor_equals_absorbed");
        }
        var survivor = productRepository.findById(survivorId)
                .orElseThrow(ProductNotFoundException::new);
        var absorbed = productRepository.findById(request.absorbedId())
                .orElseThrow(ProductNotFoundException::new);

        var counts = countMigrations(absorbed);
        if (dryRun) {
            log.info("admin.product.merge.dryrun survivor={} absorbed={} aliases={} receiptItems={} observations={} manualPurchases={} shoppingItems={} householdAliases={} snoozes={}",
                    survivorId, request.absorbedId(),
                    counts.aliases(), counts.receiptItems(), counts.observations(), counts.manualPurchases(),
                    counts.shoppingItems(), counts.householdAliases(), counts.snoozes());
            return new ProductMergeResultResponse(
                    survivorId, request.absorbedId(), true, false,
                    counts.aliases(), counts.receiptItems(), counts.observations(), counts.manualPurchases(),
                    counts.shoppingItems(), counts.householdAliases(), 0L, counts.snoozes(), 0L);
        }

        return applyMerge(survivor, absorbed);
    }

    /** Pre-count what would migrate (also serves as the dry-run output). */
    private MigrationCounts countMigrations(Product absorbed) {
        return new MigrationCounts(
                aliasRepository.countByProduct(absorbed),
                receiptItemRepository.countByProduct(absorbed),
                priceObservationRepository.countByProduct(absorbed),
                manualPurchaseRepository.countByProduct(absorbed),
                shoppingListItemRepository.countByProduct(absorbed),
                householdProductAliasRepository.countByProduct(absorbed),
                consumptionSnoozeRepository.countByProduct(absorbed));
    }

    private ProductMergeResultResponse applyMerge(Product survivor, Product absorbed) {
        // Apply: simple repoint for the no-conflict tables.
        var aliasesMoved = aliasRepository.repointProduct(absorbed, survivor);
        var receiptItemsMoved = receiptItemRepository.repointProduct(absorbed, survivor);
        var observationsMoved = priceObservationRepository.repointProduct(absorbed, survivor);
        var manualPurchasesMoved = manualPurchaseRepository.repointProduct(absorbed, survivor);
        var shoppingItemsMoved = shoppingListItemRepository.repointProduct(absorbed, survivor);

        // Tables with UNIQUE (household_id, product_id): drop absorbed's
        // entries that would conflict, then repoint the rest.
        var householdAliasesDropped = householdProductAliasRepository
                .deleteAbsorbedConflictsWithSurvivor(absorbed, survivor);
        var householdAliasesMoved = householdProductAliasRepository.repointProduct(absorbed, survivor);
        var snoozesDropped = consumptionSnoozeRepository
                .deleteAbsorbedConflictsWithSurvivor(absorbed, survivor);
        var snoozesMoved = consumptionSnoozeRepository.repointProduct(absorbed, survivor);

        productRepository.delete(absorbed);

        log.info("admin.product.merge.applied survivor={} absorbed={} aliases={} receiptItems={} observations={} manualPurchases={} shoppingItems={} householdAliases={}/{} snoozes={}/{}",
                survivor.getId(), absorbed.getId(),
                aliasesMoved, receiptItemsMoved, observationsMoved,
                manualPurchasesMoved, shoppingItemsMoved,
                householdAliasesMoved, householdAliasesDropped,
                snoozesMoved, snoozesDropped);

        return new ProductMergeResultResponse(
                survivor.getId(), absorbed.getId(), false, true,
                aliasesMoved, receiptItemsMoved, observationsMoved, manualPurchasesMoved,
                shoppingItemsMoved, householdAliasesMoved, householdAliasesDropped,
                snoozesMoved, snoozesDropped);
    }

    private record MigrationCounts(
            long aliases,
            long receiptItems,
            long observations,
            long manualPurchases,
            long shoppingItems,
            long householdAliases,
            long snoozes
    ) {}

}
