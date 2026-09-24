package com.relyon.economizaai.service.canonicalization;

import com.relyon.economizaai.config.MdcContextFilter;
import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.extraction.EanCatalogService;
import com.relyon.economizaai.service.extraction.ProductExtraction;
import com.relyon.economizaai.service.extraction.ProductExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static com.relyon.economizaai.service.canonicalization.JaroWinklerSimilarity.score;

@Slf4j
@Service
@RequiredArgsConstructor
public class CanonicalizationService {

    /**
     * Minimum Jaro-Winkler score for a candidate alias to be accepted as
     * the same product. Tuned conservatively — at 0.85 a typo or
     * abbreviation passes ("ARROZ TIO J 5KG" vs "ARROZ TIO JOAO 5KG"), but
     * unrelated products with shared prefixes ("ARROZ" vs "ARROZ DOCE")
     * stay separate.
     */
    private static final double FUZZY_MATCH_THRESHOLD = 0.85;

    private final ProductRepository productRepository;
    private final ProductAliasRepository aliasRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final ProductExtractor productExtractor;
    private final HouseholdProductAliasService householdProductAliasService;
    private final MerchantClassifier merchantClassifier;
    private final EanCatalogService eanCatalogService;

    @Transactional
    public CanonicalizationOutcome canonicalize(Receipt receipt) {
        var matched = 0;
        var created = 0;
        var unmatched = 0;
        var pharmacyMerchant = merchantClassifier.isPharmacy(receipt.getCnpjEmitente(), receipt.getMarketName());
        for (var item : receipt.getItems()) {
            if (item.isExcluded()) continue;
            MDC.put(MdcContextFilter.ITEM_ID, abbrev(item.getId()));
            try {
                var result = canonicalizeItem(item, pharmacyMerchant);
                switch (result) {
                    case MATCHED -> matched++;
                    case CREATED -> created++;
                    case UNMATCHED -> unmatched++;
                    default -> throw new IllegalStateException("Unexpected canonicalization result: " + result);
                }
                applyHouseholdFriendlyName(receipt, item);
            } finally {
                MDC.remove(MdcContextFilter.ITEM_ID);
            }
        }
        log.info("canonicalize done matched={} created={} unmatched={}", matched, created, unmatched);
        return new CanonicalizationOutcome(matched, created, unmatched);
    }

    /**
     * Resolve a single item to a canonical {@link Product}, linking it in place
     * and returning the product. Runs the same matching cascade as
     * {@link #canonicalize} (EAN → exact alias → fuzzy alias) but, unlike the
     * batch path, NEVER leaves the item unmatched: when no EAN-less item matches
     * an alias, it creates a product from the item's description rather than
     * returning UNMATCHED.
     *
     * <p>Used when a user explicitly categorizes an unrecognized item on the
     * review screen — the correction needs a product to anchor the household
     * override, and forcing creation lets that correction propagate to future
     * purchases of the same item (matched by the alias created here).
     */
    @Transactional
    public Product linkOrCreateProduct(Receipt receipt, ReceiptItem item) {
        if (item.getProduct() != null) {
            return item.getProduct();
        }
        MDC.put(MdcContextFilter.ITEM_ID, abbrev(item.getId()));
        try {
            var pharmacyMerchant = merchantClassifier.isPharmacy(receipt.getCnpjEmitente(), receipt.getMarketName());
            var normalized = DescriptionNormalizer.normalize(item.getRawDescription());
            if (hasEan(item)) {
                canonicalizeByEan(item, normalized, pharmacyMerchant);
            } else if (canonicalizeByAlias(item, normalized, pharmacyMerchant) == ItemResult.UNMATCHED) {
                createProductFromDescription(item, normalized,
                        productExtractor.extract(item.getRawDescription()), pharmacyMerchant);
            }
            applyHouseholdFriendlyName(receipt, item);
            return item.getProduct();
        } finally {
            MDC.remove(MdcContextFilter.ITEM_ID);
        }
    }

    /**
     * Re-runs canonicalization on the confirmed UNMATCHED items whose description
     * contains {@code keyword} as a contiguous token phrase — used right after an
     * admin saves a curated rule so the backlog those items form drops out of the
     * "não-casados" queue. Returns how many got linked to a product.
     */
    @Transactional
    public int recanonicalizeUnmatchedForKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return 0;
        var normalizedKeyword = DescriptionNormalizer.normalize(keyword);
        if (normalizedKeyword.isBlank()) return 0;
        var candidates = receiptItemRepository.findUnmatchedConfirmedByDescriptionLike(
                "%" + keyword.trim().toLowerCase() + "%");
        var relinked = 0;
        for (var item : candidates) {
            if (!descriptionContainsPhrase(item.getRawDescription(), normalizedKeyword)) continue;
            linkOrCreateProduct(item.getReceipt(), item);
            if (item.getProduct() != null) relinked++;
        }
        log.info("recanonicalize.keyword keyword='{}' candidates={} relinked={}", keyword, candidates.size(), relinked);
        return relinked;
    }

    /** True when the normalized description contains the normalized phrase as whole, contiguous tokens. */
    private static boolean descriptionContainsPhrase(String rawDescription, String normalizedPhrase) {
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return false;
        return (" " + normalized + " ").contains(" " + normalizedPhrase + " ");
    }

    /**
     * Create a product for an EAN-less item the alias cascade couldn't place.
     * Mirrors {@link #createProductFromEan} but the source description is the
     * only signal, so there's no EAN to enrich from.
     */
    private ItemResult createProductFromDescription(ReceiptItem item, String normalized,
                                                    ProductExtraction extraction,
                                                    boolean pharmacyMerchant) {
        var newProduct = buildEnrichedProduct(item, extraction);
        applyPharmacyMerchantFallback(newProduct, pharmacyMerchant);
        var created = productRepository.save(newProduct);
        item.setProduct(created);
        ensureAlias(created, item.getRawDescription(), normalized);
        log.info("item.created_from_description product={} description='{}'",
                abbrev(created.getId()), item.getRawDescription());
        return ItemResult.CREATED;
    }

    private void applyHouseholdFriendlyName(Receipt receipt, ReceiptItem item) {
        // Skip if user already typed a name on this item, or item didn't get
        // linked to a Product, or there's no household memory for this product.
        if (item.getFriendlyDescription() != null && !item.getFriendlyDescription().isBlank()) return;
        if (item.getProduct() == null) return;
        var remembered = householdProductAliasService.findFor(receipt.getHousehold(), item.getProduct());
        if (remembered != null) {
            item.setFriendlyDescription(remembered);
            log.info("item.inherited_friendly_name product={} name='{}'",
                    abbrev(item.getProduct().getId()), remembered);
        }
    }

    /**
     * Links one item to a canonical {@link Product} by trying each matching
     * strategy in priority order:
     * <ol>
     *   <li>already linked → nothing to do;</li>
     *   <li>has EAN → exact code, metadata dedup, or create-new (the EAN path
     *       always resolves, hence its own terminal branch);</li>
     *   <li>no EAN → exact-alias, fuzzy-alias, then create only when the
     *       description extractor produced a usable category/generic signal.</li>
     * </ol>
     * Falls through to UNMATCHED only when an EAN-less item matches no alias
     * and the description is too weak to seed a product automatically.
     */
    /**
     * Read-only preview of the category {@link #canonicalize} would assign to an
     * unlinked item — same priority order (EAN product → EAN catalog → exact
     * alias → dictionary/extraction) but nothing is created, linked or
     * persisted. Used by the review screen so the user sees our best effort
     * before confirming. Fuzzy alias matching is skipped (scans every alias —
     * too heavy per GET; confirm still applies it).
     */
    @Transactional(readOnly = true)
    public Optional<ProductCategory> previewCategory(ReceiptItem item) {
        if (hasEan(item)) {
            var byEan = productRepository.findByEan(item.getEan());
            if (byEan.isPresent() && byEan.get().getCategory() != null) {
                return Optional.of(byEan.get().getCategory());
            }
            var catalogCategory = eanCatalogService.lookup(item.getEan())
                    .map(EanCatalogEntry::getCategory).orElse(null);
            if (catalogCategory != null) {
                return Optional.of(catalogCategory);
            }
        }
        var normalized = DescriptionNormalizer.normalize(item.getRawDescription());
        var byAlias = aliasRepository.findByNormalizedDescription(normalized);
        if (byAlias.isPresent() && byAlias.get().getProduct().getCategory() != null) {
            return Optional.of(byAlias.get().getProduct().getCategory());
        }
        return Optional.ofNullable(productExtractor.extract(item.getRawDescription()).category());
    }

    private ItemResult canonicalizeItem(ReceiptItem item, boolean pharmacyMerchant) {
        if (item.getProduct() != null) {
            log.info("item.skip already linked product={}", abbrev(item.getProduct().getId()));
            return ItemResult.MATCHED;
        }

        var normalized = DescriptionNormalizer.normalize(item.getRawDescription());

        if (hasEan(item)) {
            return canonicalizeByEan(item, normalized, pharmacyMerchant);
        }
        return canonicalizeByAlias(item, normalized, pharmacyMerchant);
    }

    /**
     * EAN path: exact code match → metadata dedup → create a new product.
     * Always terminal — an item with an EAN ends up either matched or created,
     * never unmatched.
     */
    private ItemResult canonicalizeByEan(ReceiptItem item, String normalized, boolean pharmacyMerchant) {
        // A1 — produto já existe na nossa base (EAN já foi visto antes)
        var byEan = productRepository.findByEan(item.getEan());
        if (byEan.isPresent()) {
            item.setProduct(byEan.get());
            ensureAlias(byEan.get(), item.getRawDescription(), normalized);
            log.info("item.matched_by_ean ean={} product={}", item.getEan(), abbrev(byEan.get().getId()));
            return ItemResult.MATCHED;
        }
        // A2 — EAN no catálogo externo (Open Food Facts / import admin)
        var catalogEntry = eanCatalogService.lookup(item.getEan()).orElse(null);
        if (catalogEntry != null) {
            log.info("item.catalog_hit ean={} category={} source={}",
                    item.getEan(), catalogEntry.getCategory(), catalogEntry.getSource());
        }
        var extraction = productExtractor.extract(item.getRawDescription());
        var dedup = tryMetadataDedup(extraction);
        if (dedup != null) {
            item.setProduct(dedup);
            ensureAlias(dedup, item.getRawDescription(), normalized);
            log.info("item.matched_by_metadata ean={} product={} brand={} pack={}{}",
                    item.getEan(), abbrev(dedup.getId()), extraction.brand(),
                    extraction.packSize(), extraction.packUnit());
            return ItemResult.MATCHED;
        }
        return createProductFromEan(item, normalized, extraction, catalogEntry, pharmacyMerchant);
    }

    private ItemResult createProductFromEan(ReceiptItem item, String normalized,
                                            ProductExtraction extraction,
                                            EanCatalogEntry catalogEntry,
                                            boolean pharmacyMerchant) {
        var newProduct = buildEnrichedProduct(item, extraction);
        applyCatalogEnrichment(newProduct, catalogEntry);
        applyPharmacyMerchantFallback(newProduct, pharmacyMerchant);
        var created = productRepository.save(newProduct);
        item.setProduct(created);
        ensureAlias(created, item.getRawDescription(), normalized);
        log.info("item.created_from_ean ean={} product={} description='{}' extracted={}",
                item.getEan(), abbrev(created.getId()), item.getRawDescription(), extraction);
        return ItemResult.CREATED;
    }

    /**
     * No-EAN path: exact-alias match → fuzzy-alias match → create from
     * dictionary-backed extraction. Unlike the EAN path, a no-signal item is
     * left UNMATCHED for manual review rather than spawning a low-confidence row.
     */
    private ItemResult canonicalizeByAlias(ReceiptItem item, String normalized, boolean pharmacyMerchant) {
        var byAlias = aliasRepository.findByNormalizedDescription(normalized);
        if (byAlias.isPresent()) {
            item.setProduct(byAlias.get().getProduct());
            log.info("item.matched_by_alias product={} normalized='{}'",
                    abbrev(byAlias.get().getProduct().getId()), normalized);
            return ItemResult.MATCHED;
        }
        var fuzzy = tryFuzzyAliasMatch(item, normalized);
        if (fuzzy != null) {
            item.setProduct(fuzzy.getProduct());
            ensureAlias(fuzzy.getProduct(), item.getRawDescription(), normalized);
            return ItemResult.MATCHED;
        }
        var extraction = productExtractor.extract(item.getRawDescription());
        if (canCreateFromDescription(extraction, pharmacyMerchant)) {
            return createProductFromDescription(item, normalized, extraction, pharmacyMerchant);
        }
        log.info("item.unmatched description='{}' (no EAN, no alias) — needs review", item.getRawDescription());
        return ItemResult.UNMATCHED;
    }

    private boolean canCreateFromDescription(ProductExtraction extraction, boolean pharmacyMerchant) {
        return extraction.genericName() != null || extraction.category() != null || pharmacyMerchant;
    }

    /**
     * Dedup gate before creating a new {@link Product} for an unknown EAN.
     * Some markets (especially small mercadinhos) emit internal pseudo-EANs
     * for the same physical product, which would otherwise inflate the
     * catalog with duplicates. When all four metadata dimensions are
     * extracted with confidence and an existing product matches them
     * exactly, link to it instead of creating a new one. The new EAN is
     * deliberately NOT propagated onto the existing product — keeping
     * {@code Product.ean} as a single canonical code per row simplifies
     * downstream queries; the new alias takes care of future descriptions.
     *
     * <p>Returns null (→ fall through to create-new) when:
     * <ul>
     *   <li>any of genericName / brand / packSize / packUnit is missing,</li>
     *   <li>no product matches the full profile.</li>
     * </ul>
     * If multiple match (already-duplicated catalog rows), picks the first —
     * the admin merge tool is the right place to consolidate the rest.
     */
    private Product tryMetadataDedup(ProductExtraction extraction) {
        if (extraction.genericName() == null || extraction.brand() == null
                || extraction.packSize() == null || extraction.packUnit() == null) {
            return null;
        }
        var matches = productRepository.findByMetadata(
                extraction.genericName(), extraction.brand(),
                extraction.packSize(), extraction.packUnit());
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Last-resort fuzzy alias match. Runs only when EAN is absent AND no
     * exact-alias match was found. Restricts the candidate pool to aliases
     * of products with the same extracted (genericName, packSize, packUnit)
     * profile, then picks the candidate with the highest Jaro-Winkler score
     * if it clears {@link #FUZZY_MATCH_THRESHOLD}. Returns null when:
     *
     * <ul>
     *   <li>any of the three metadata dimensions is missing — without them
     *       the candidate filter is too loose (would scan unrelated products).</li>
     *   <li>no candidate clears the threshold.</li>
     * </ul>
     */
    private ProductAlias tryFuzzyAliasMatch(ReceiptItem item, String normalized) {
        var extraction = productExtractor.extract(item.getRawDescription());
        if (extraction.genericName() == null || extraction.packSize() == null || extraction.packUnit() == null) {
            return null;
        }
        var candidates = aliasRepository.findCandidatesByProductMetadata(
                extraction.genericName(), extraction.packSize(), extraction.packUnit());
        ProductAlias best = null;
        var bestScore = 0.0;
        for (var candidate : candidates) {
            var similarity = score(normalized, candidate.getNormalizedDescription());
            if (similarity > bestScore) {
                bestScore = similarity;
                best = candidate;
            }
        }
        if (best == null || bestScore < FUZZY_MATCH_THRESHOLD) {
            return null;
        }
        log.info("item.matched_by_fuzzy product={} normalized='{}' candidate='{}' score={}",
                abbrev(best.getProduct().getId()), normalized, best.getNormalizedDescription(),
                String.format("%.3f", bestScore));
        return best;
    }

    private boolean hasEan(ReceiptItem item) {
        return item.getEan() != null && !item.getEan().isBlank();
    }

    /**
     * Enriches a newly built product with data from the EAN catalog (Open Food
     * Facts or admin import).  Only fills NULL fields — extraction results from
     * the description text (brand registry, pack-size regex) are kept when
     * present.  Category from the catalog wins over the keyword dictionary
     * because it is EAN-specific, not description-based.
     */
    private void applyCatalogEnrichment(Product product, EanCatalogEntry entry) {
        if (entry == null) return;
        if (entry.getCategory() != null) {
            product.setCategory(entry.getCategory());
            product.setCategorizationSource(CategorizationSource.DICTIONARY); // catalog is curated data
        }
        if (product.getGenericName() == null && entry.getGenericName() != null) {
            product.setGenericName(entry.getGenericName());
        }
        if (product.getBrand() == null && entry.getBrand() != null) {
            product.setBrand(entry.getBrand());
        }
    }

    /**
     * Fallback for items the dictionary couldn't place that were bought at a
     * pharmacy: default them to PHARMACY instead of OTHER. Only fills a blank
     * or OTHER category — items the cascade already classified (e.g. candy or
     * cleaning bought at a drugstore) keep their category.
     */
    private void applyPharmacyMerchantFallback(Product product, boolean pharmacyMerchant) {
        if (!pharmacyMerchant) return;
        if (product.getCategory() != null && product.getCategory() != ProductCategory.OTHER) return;
        product.setCategory(ProductCategory.HEALTH);
        product.setCategorizationSource(CategorizationSource.MERCHANT);
        log.info("item.category_from_pharmacy_merchant description='{}'", product.getNormalizedName());
    }

    private Product buildEnrichedProduct(ReceiptItem item, ProductExtraction extraction) {
        return Product.builder()
                .ean(item.getEan())
                .normalizedName(item.getRawDescription())
                .unit(item.getUnit())
                .genericName(extraction.genericName())
                .brand(extraction.brand())
                .category(extraction.category())
                .packSize(extraction.packSize())
                .packUnit(extraction.packUnit())
                .categorizationSource(extraction.categorizationSource())
                .build();
    }

    private void ensureAlias(Product product, String rawDescription, String normalized) {
        if (normalized.isBlank() || aliasRepository.existsByNormalizedDescription(normalized)) {
            return;
        }
        aliasRepository.save(ProductAlias.builder()
                .product(product)
                .rawDescription(rawDescription)
                .normalizedDescription(normalized)
                .build());
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }

    public enum ItemResult { MATCHED, CREATED, UNMATCHED }

    public record CanonicalizationOutcome(int matched, int created, int unmatched) {}
}
