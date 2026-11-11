package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.CreateAliasRequest;
import com.relyon.economizaai.dto.request.CreateProductRequest;
import com.relyon.economizaai.dto.request.UpdateProductRequest;
import com.relyon.economizaai.dto.response.EanLookupResponse;
import com.relyon.economizaai.dto.response.ProductResponse;
import com.relyon.economizaai.dto.response.UnmatchedItemResponse;
import com.relyon.economizaai.exception.EanConflictException;
import com.relyon.economizaai.exception.ProductAliasConflictException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.EanCatalogService;
import com.relyon.economizaai.service.extraction.BrandAliasPromotionService;
import com.relyon.economizaai.service.extraction.ProductExtractor;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final double PRODUCT_SEARCH_NEARBY_RADIUS_KM = 20.0;
    private static final int MIN_CATALOG_QUERY_LENGTH = 2;
    private static final int MAX_SEARCH_CANDIDATES = 200;

    private final ProductRepository productRepository;
    private final CategoryResolver categoryResolver;
    private final ProductAliasRepository aliasRepository;
    private final HouseholdProductAliasRepository householdProductAliasRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final ProductExtractor productExtractor;
    private final EanCatalogService eanCatalogService;
    private final HouseholdProductAliasService householdProductAliasService;
    private final BrandAliasPromotionService brandAliasPromotionService;
    private final ViewerLocationService viewerLocationService;

    /**
     * Product search for the app's picker. Bounded on purpose — it runs per keystroke:
     * <ul>
     *   <li>no term → the household's own products, most purchased first (paged in SQL);</li>
     *   <li>a term shorter than {@value #MIN_CATALOG_QUERY_LENGTH} characters → only the household's
     *       own products matching it (the catalog is not searched);</li>
     *   <li>otherwise → at most {@value #MAX_SEARCH_CANDIDATES} catalog candidates per source, ranked
     *       by local relevance and paged over that capped set.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> search(String query, User user, Pageable pageable) {
        var trimmedQuery = (query == null || query.isBlank()) ? null : query.trim();
        var householdId = user.getHousehold().getId();
        if (trimmedQuery == null) {
            return mostPurchasedByHousehold(householdId, pageable);
        }
        var products = searchCandidates(trimmedQuery, householdId);
        if (products.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        var productIds = products.stream().map(Product::getId).toList();
        var ranking = ProductSearchRanking.from(productIds, householdId, viewerLocationService.resolve(user),
                receiptItemRepository, priceObservationRepository);
        var friendlyByProduct = householdProductAliasService.resolvedNamesFor(householdId, productIds);
        var sorted = products.stream()
                .sorted(ranking.comparator())
                .map(product -> ProductResponse.from(product, ranking.wasBought(product.getId()),
                        friendlyByProduct.get(product.getId())))
                .toList();
        return page(sorted, pageable);
    }

    private Page<ProductResponse> mostPurchasedByHousehold(UUID householdId, Pageable pageable) {
        var idsPage = receiptItemRepository.findMostPurchasedProductIdsForHousehold(householdId, withoutSort(pageable));
        var productIds = idsPage.getContent();
        if (productIds.isEmpty()) {
            return new PageImpl<>(List.of(), idsPage.getPageable(), idsPage.getTotalElements());
        }
        var productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        var friendlyByProduct = householdProductAliasService.resolvedNamesFor(householdId, productIds);
        var content = productIds.stream()
                .map(productsById::get)
                .filter(Objects::nonNull)
                .map(product -> ProductResponse.from(product, true, friendlyByProduct.get(product.getId())))
                .toList();
        return new PageImpl<>(content, idsPage.getPageable(), idsPage.getTotalElements());
    }

    /** The ordering is fixed by the query; a client-sent sort must not reach the JPQL. */
    private static Pageable withoutSort(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, MAX_SEARCH_CANDIDATES);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    /**
     * The capped candidate set for a term: catalog matches by name/EAN and by absorbed receipt
     * description (only from {@value #MIN_CATALOG_QUERY_LENGTH} characters on), plus what the
     * household itself bought or renamed — so its own products are never cut by the cap.
     */
    private List<Product> searchCandidates(String query, UUID householdId) {
        var pattern = containsPattern(query);
        var limit = PageRequest.of(0, MAX_SEARCH_CANDIDATES);
        var candidatesById = new LinkedHashMap<UUID, Product>();
        if (query.length() >= MIN_CATALOG_QUERY_LENGTH) {
            addCandidates(candidatesById, productRepository.searchCatalogByNameOrEan(query, pattern, limit));
            addCandidates(candidatesById, productRepository.searchCatalogByAliasDescription(pattern, limit));
        }
        addCandidates(candidatesById, productRepository.searchPurchasedByHousehold(householdId, query, pattern, limit));
        addCandidates(candidatesById, householdProductAliasRepository.findProductsByFriendlyNameContaining(householdId, query));
        return List.copyOf(candidatesById.values());
    }

    private static void addCandidates(Map<UUID, Product> candidatesById, List<Product> products) {
        products.forEach(product -> candidatesById.putIfAbsent(product.getId(), product));
    }

    /** {@code %term%} lowercased, with LIKE wildcards in the term escaped by '!' (the queries' ESCAPE char). */
    static String containsPattern(String query) {
        var escaped = query.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id, User user) {
        var product = loadProduct(id);
        return ProductResponse.from(product, false,
                householdProductAliasService.resolvedNameFor(user.getHousehold().getId(), product.getId()));
    }

    /**
     * Barcode-scan lookup: resolve the EAN to a tracked product (price queries
     * possible), or fall back to an EAN-catalog preview (product known to the
     * catalog but never scanned in a receipt — no price data yet). 404 when the
     * barcode is unknown to both.
     */
    @Transactional(readOnly = true)
    public EanLookupResponse lookupByEan(String ean, User user) {
        var digits = ean == null ? "" : ean.replaceAll("\\D", "");
        if (digits.length() < 8) {
            throw new ProductNotFoundException();
        }
        var product = productRepository.findByEan(digits);
        if (product.isPresent()) {
            return EanLookupResponse.ofProduct(
                    ProductResponse.from(product.get(), false,
                            householdProductAliasService.resolvedNameFor(user.getHousehold().getId(), product.get().getId())));
        }
        return eanCatalogService.lookup(digits)
                .map(EanLookupResponse::ofCatalog)
                .orElseThrow(ProductNotFoundException::new);
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (request.ean() != null && !request.ean().isBlank()
                && productRepository.findByEan(request.ean()).isPresent()) {
            throw new EanConflictException(request.ean());
        }
        var extracted = productExtractor.extract(request.normalizedName());
        var category = request.category() != null ? request.category() : extracted.category();
        // When the user didn't pick a category, the source follows the extractor —
        // but only if it actually produced one; otherwise there's no categorization.
        var extractedSource = category != null ? extracted.categorizationSource() : CategorizationSource.NONE;
        var source = request.category() != null ? CategorizationSource.USER : extractedSource;
        var newProduct = Product.builder()
                .ean(blankToNull(request.ean()))
                .normalizedName(request.normalizedName())
                .genericName(firstNonBlank(request.genericName(), extracted.genericName()))
                .brand(firstNonBlank(request.brand(), extracted.brand()))
                .category(category)
                .categoryId(extracted.categoryId()) // alinhado logo abaixo via alignNewProduct
                .unit(blankToNull(request.unit()))
                .packSize(request.packSize() != null ? request.packSize() : extracted.packSize())
                .packUnit(firstNonBlank(request.packUnit(), extracted.packUnit()))
                .categorizationSource(source)
                .build();
        categoryResolver.alignNewProduct(newProduct); // enum↔nó consistentes (listener não persiste campo cruzado)
        var product = productRepository.save(newProduct);
        log.info("Product {} created (ean={}, name='{}')", product.getId(), product.getEan(), product.getNormalizedName());
        if (product.getEan() != null) {
            var linked = receiptItemRepository.linkByEan(product, product.getEan());
            log.info("Backfilled {} receipt items by EAN {}", linked, product.getEan());
        }
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        var product = loadProduct(id);
        product.setNormalizedName(request.normalizedName());
        product.setGenericName(blankToNull(request.genericName()));
        product.setGenericNameNorm(DescriptionNormalizer.normalizeOrNull(request.genericName()));
        product.setBrand(blankToNull(request.brand()));
        product.setBrandNorm(DescriptionNormalizer.normalizeOrNull(request.brand()));
        product.setCategory(request.category());
        // Escritor de ENUM: move o nó junto (listener é node-first — sem isto o nó atual venceria).
        product.setCategoryId(categoryResolver.reconcileNode(request.category(), product.getCategoryId()));
        product.setUnit(blankToNull(request.unit()));
        product.setPackSize(request.packSize());
        product.setPackUnit(blankToNull(request.packUnit()));
        // any manual update (PATCH) becomes the highest-trust signal — USER overrides any prior source
        product.setCategorizationSource(request.category() != null
                ? CategorizationSource.USER
                : CategorizationSource.NONE);
        var saved = productRepository.save(product);
        log.info("Product {} updated source=USER", saved.getId());
        learnBrandAliasesFrom(saved);
        return ProductResponse.from(saved);
    }

    /**
     * When an admin sets a product's brand, learn any abbreviated forms of that
     * brand appearing in its receipt descriptions as registry aliases — so the
     * correction propagates to future scans without waiting for the backfill.
     */
    private void learnBrandAliasesFrom(Product product) {
        if (product.getBrand() == null || product.getBrand().isBlank()) return;
        var descriptions = aliasRepository.findByProductIdIn(List.of(product.getId())).stream()
                .map(ProductAlias::getRawDescription)
                .toList();
        var learned = brandAliasPromotionService.promoteForProduct(product.getBrand(), descriptions);
        if (learned > 0) log.info("Product {} brand aliases learned={}", product.getId(), learned);
    }

    @Transactional
    public ProductResponse addAlias(User user, UUID productId, CreateAliasRequest request) {
        var product = loadProduct(productId);
        var normalized = DescriptionNormalizer.normalize(request.rawDescription());
        if (normalized.isBlank() || aliasRepository.existsByNormalizedDescription(normalized)) {
            throw new ProductAliasConflictException(request.rawDescription());
        }
        aliasRepository.save(ProductAlias.builder()
                .product(product)
                .rawDescription(request.rawDescription())
                .normalizedDescription(normalized)
                .build());

        var unmatched = receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId());
        var linked = backfillByDescription(unmatched, normalized, product);
        log.info("Alias '{}' → product {}; backfilled {} items in household {}",
                normalized, product.getId(), linked, user.getHousehold().getId());
        return ProductResponse.from(product, false,
                householdProductAliasService.resolvedNameFor(user.getHousehold().getId(), product.getId()));
    }

    @Transactional(readOnly = true)
    public List<UnmatchedItemResponse> listUnmatched(User user) {
        return receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId()).stream()
                .map(this::toUnmatchedResponse)
                .toList();
    }

    private int backfillByDescription(List<ReceiptItem> candidates, String normalized, Product product) {
        var linked = 0;
        for (var item : candidates) {
            var itemNormalized = DescriptionNormalizer.normalize(item.getRawDescription());
            if (itemNormalized.equals(normalized)) {
                item.setProduct(product);
                receiptItemRepository.save(item);
                linked++;
            }
        }
        return linked;
    }

    private Product loadProduct(UUID id) {
        return productRepository.findById(id).orElseThrow(ProductNotFoundException::new);
    }

    private Page<ProductResponse> page(List<ProductResponse> sorted, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return new PageImpl<>(sorted);
        }
        var start = (int) Math.min(pageable.getOffset(), sorted.size());
        var end = Math.min(start + pageable.getPageSize(), sorted.size());
        return new PageImpl<>(sorted.subList(start, end), pageable, sorted.size());
    }

    private UnmatchedItemResponse toUnmatchedResponse(ReceiptItem item) {
        var receipt = item.getReceipt();
        return new UnmatchedItemResponse(
                item.getId(),
                receipt.getId(),
                receipt.getMarketName(),
                receipt.getIssuedAt(),
                item.getRawDescription(),
                item.getEan(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                item.getUnit()
        );
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        var preferredValue = blankToNull(preferred);
        return preferredValue != null ? preferredValue : blankToNull(fallback);
    }

    private record ProductSearchRanking(Set<UUID> bought,
                                        Set<UUID> observedAtVisitedMarkets,
                                        Set<UUID> observedNearby,
                                        Set<UUID> observedInHouseholdCities) {

        private static ProductSearchRanking from(List<UUID> productIds, UUID householdId, ViewerLocation viewerLocation,
                                                 ReceiptItemRepository receiptItemRepository,
                                                 PriceObservationRepository priceObservationRepository) {
            var bought = Set.copyOf(
                    receiptItemRepository.findProductIdsWithHistoryForHousehold(productIds, householdId));
            var visitedMarkets = Set.copyOf(
                    priceObservationRepository.findProductIdsObservedAtVisitedMarkets(productIds, householdId));
            var householdCities = Set.copyOf(
                    priceObservationRepository.findProductIdsObservedInHouseholdCities(productIds, householdId));
            var nearby = nearbyProductIds(productIds, viewerLocation, priceObservationRepository);
            return new ProductSearchRanking(bought, visitedMarkets, nearby, householdCities);
        }

        /** Explicit GPS/CEP or, without one, the centre inferred from the household's receipts. */
        private static Set<UUID> nearbyProductIds(List<UUID> productIds, ViewerLocation viewerLocation,
                                                  PriceObservationRepository priceObservationRepository) {
            if (!viewerLocation.hasCoordinates()) {
                return Set.of();
            }
            return priceObservationRepository.findProductMarketCoordinates(productIds).stream()
                    .filter(row -> DistanceCalculator.kmBetween(
                            viewerLocation.latitude(), viewerLocation.longitude(),
                            row.getLatitude(), row.getLongitude()) <= PRODUCT_SEARCH_NEARBY_RADIUS_KM)
                    .map(PriceObservationRepository.ProductMarketCoordinates::getProductId)
                    .collect(Collectors.toSet());
        }

        private Comparator<Product> comparator() {
            return Comparator
                    .comparingInt((Product product) -> bought.contains(product.getId()) ? 0 : 1)
                    .thenComparingInt(product -> observedAtVisitedMarkets.contains(product.getId()) ? 0 : 1)
                    .thenComparingInt(product -> observedNearby.contains(product.getId()) ? 0 : 1)
                    .thenComparingInt(product -> observedInHouseholdCities.contains(product.getId()) ? 0 : 1)
                    .thenComparing(Product::getNormalizedName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        }

        private boolean wasBought(UUID productId) {
            return bought.contains(productId);
        }
    }
}
