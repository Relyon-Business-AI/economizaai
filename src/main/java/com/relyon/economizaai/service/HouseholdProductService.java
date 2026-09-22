package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.HouseholdProductResponse;
import com.relyon.economizaai.dto.response.ProductMarketPriceResponse;
import com.relyon.economizaai.dto.response.ProductMarketPriceResponse.PriceType;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Household-scoped product views:
 * <ul>
 *   <li>{@link #listHouseholdProducts} — products the household has actually bought
 *       (not the global catalog), with the household's own last-purchase context.</li>
 *   <li>{@link #productMarkets} — "onde está mais barato": per-market price for a
 *       product across the household's watched markets (always) and nearby markets
 *       (opt-in). Own visited markets show the exact last paid price; community
 *       markets show the k-anonymity-guarded median (never a sub-K single price).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdProductService {

    private static final int MARKET_LIMIT = 50;

    private final ProductRepository productRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final HouseholdProductAliasRepository householdProductAliasRepository;
    private final PriceIndexService priceIndexService;
    private final WatchedMarketService watchedMarketService;
    private final MarketLocationService marketLocationService;
    private final MarketNameService marketNameService;

    @Transactional(readOnly = true)
    public List<HouseholdProductResponse> listHouseholdProducts(User user, String query) {
        var householdId = user.getHousehold().getId();
        var byProduct = aggregatePurchaseHistory(householdId);
        if (byProduct.isEmpty()) return List.of();

        var cnpjs = byProduct.values().stream().map(aggregate -> aggregate.lastCnpj).distinct().toList();
        var overrides = marketNameService.resolveNames(householdId, cnpjs);
        var friendlyNames = friendlyNamesByProduct(householdId, byProduct.keySet());

        return byProduct.values().stream()
                .map(aggregate -> aggregate.toResponse(overrides,
                        friendlyNames.get(aggregate.product.getId())))
                .filter(resp -> matchesQuery(resp, query))
                .sorted(Comparator.comparing(HouseholdProductResponse::lastBoughtAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** The household's own renames (household_product_aliases), keyed by product id. */
    private Map<UUID, String> friendlyNamesByProduct(UUID householdId, Set<UUID> productIds) {
        return householdProductAliasRepository
                .findAllByHouseholdIdAndProductIdIn(householdId, new ArrayList<>(productIds))
                .stream()
                .collect(Collectors.toMap(alias -> alias.getProduct().getId(),
                        HouseholdProductAlias::getFriendlyName));
    }

    private boolean matchesQuery(HouseholdProductResponse resp, String query) {
        if (query == null || query.isBlank()) return true;
        var lower = query.strip().toLowerCase();
        return (resp.name() != null && resp.name().toLowerCase().contains(lower))
                || (resp.friendlyName() != null && resp.friendlyName().toLowerCase().contains(lower))
                || (resp.brand() != null && resp.brand().toLowerCase().contains(lower));
    }

    /** Collapse the household's confirmed history into one Aggregate per product
     *  (fed oldest -> newest, so the last item seen per product is the most recent). */
    private LinkedHashMap<UUID, Aggregate> aggregatePurchaseHistory(UUID householdId) {
        var history = receiptItemRepository.findConfirmedHistoryForHousehold(householdId);
        var byProduct = new LinkedHashMap<UUID, Aggregate>();
        for (var item : history) {
            var product = item.getProduct();
            if (product == null) continue;
            byProduct.computeIfAbsent(product.getId(), key -> new Aggregate(product)).accept(item);
        }
        return byProduct;
    }

    @Transactional(readOnly = true)
    public List<ProductMarketPriceResponse> productMarkets(User user, UUID productId,
                                                           boolean includeNearby, Double radiusKm) {
        productRepository.findById(productId).orElseThrow(ProductNotFoundException::new);
        var householdId = user.getHousehold().getId();
        var watchedCnpjs = watchedMarketService.watchedCnpjs(user);
        var rowsByCnpj = new LinkedHashMap<String, ProductMarketPriceResponse>();

        collectOwnPriceRows(user, productId, householdId, watchedCnpjs, rowsByCnpj);
        overlayCommunityPriceRows(user, productId, includeNearby, radiusKm, watchedCnpjs, rowsByCnpj);

        var overrides = marketNameService.resolveNames(householdId, rowsByCnpj.keySet());
        return rowsByCnpj.values().stream()
                .map(row -> withFriendlyName(row, overrides))
                .sorted(Comparator.comparing(ProductMarketPriceResponse::price,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Own data: the household's own last-paid price at each market it shopped
     * at — exact, no k-anon needed. Last purchase per CNPJ wins (history is
     * oldest -> newest).
     */
    private void collectOwnPriceRows(User user, UUID productId, UUID householdId, Set<String> watchedCnpjs,
                                     Map<String, ProductMarketPriceResponse> rowsByCnpj) {
        var ownHistory = receiptItemRepository.findHouseholdHistoryForProduct(productId, householdId);
        var ownLatest = new LinkedHashMap<String, ReceiptItem>();
        for (var item : ownHistory) { // oldest -> newest, last wins
            var cnpj = item.getReceipt().getCnpjEmitente();
            if (cnpj != null) ownLatest.put(cnpj, item);
        }
        var ownLocations = marketLocationService.findByCnpjs(new ArrayList<>(ownLatest.keySet()));
        for (var entry : ownLatest.entrySet()) {
            var item = entry.getValue();
            var marketName = item.getReceipt().getMarketName();
            rowsByCnpj.put(entry.getKey(), new ProductMarketPriceResponse(
                    entry.getKey(), PriceIndexService.cnpjRoot(entry.getKey()),
                    marketName, marketName,
                    item.getUnitPrice(), PriceType.OWN_LAST,
                    null, null, null,
                    distance(user, ownLocations.get(entry.getKey())),
                    watchedCnpjs.contains(entry.getKey()), true,
                    item.getReceipt().getIssuedAt()));
        }
    }

    /**
     * Community data: k-anon-guarded medians for watched (always) + nearby
     * (opt-in) markets. A market where the household has its own exact price is
     * skipped — own price wins over the community median.
     */
    private void overlayCommunityPriceRows(User user, UUID productId, boolean includeNearby, Double radiusKm,
                                           Set<String> watchedCnpjs,
                                           Map<String, ProductMarketPriceResponse> rowsByCnpj) {
        var communityRows = priceIndexService.bestMarkets(productId, MARKET_LIMIT,
                user.getHomeLatitude(), user.getHomeLongitude(),
                includeNearby ? radiusKm : null, watchedCnpjs);
        for (var row : communityRows) {
            if (rowsByCnpj.containsKey(row.cnpj())) continue; // own exact price wins over community median
            var medianPrice = row.medianPrice() != null
                    ? row.medianPrice().setScale(2, RoundingMode.HALF_UP)
                    : null;
            rowsByCnpj.put(row.cnpj(), new ProductMarketPriceResponse(
                    row.cnpj(), row.cnpjRoot(), row.marketName(), row.marketName(),
                    medianPrice, PriceType.COMMUNITY_MEDIAN,
                    row.minPrice(), row.sampleCount(), row.distinctHouseholds(),
                    row.distanceKm(), row.watching(), false, null));
        }
    }

    /** Set friendlyName to the household rename when present; leave the original marketName intact. */
    private ProductMarketPriceResponse withFriendlyName(ProductMarketPriceResponse row, Map<String, String> overrides) {
        var friendly = marketNameService.applyOverride(overrides, row.cnpj(), row.marketName());
        if (friendly == null || friendly.equals(row.friendlyName())) return row;
        return new ProductMarketPriceResponse(row.cnpj(), row.cnpjRoot(), row.marketName(), friendly,
                row.price(), row.priceType(), row.communityMinPrice(), row.sampleCount(),
                row.distinctHouseholds(), row.distanceKm(), row.watched(), row.visited(), row.observedAt());
    }

    private Double distance(User user, MarketLocation location) {
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null
                || location == null || !location.hasCoordinates()) {
            return null;
        }
        return DistanceCalculator.kmBetween(
                user.getHomeLatitude(), user.getHomeLongitude(),
                location.getLatitude(), location.getLongitude());
    }

    /** Per-product accumulator over the household's purchase history (fed oldest -> newest). */
    private static final class Aggregate {
        private final Product product;
        private long timesBought;
        private LocalDateTime lastBoughtAt;
        private BigDecimal lastUnitPrice;
        private String lastCnpj;
        private String lastMarketName;

        private Aggregate(Product product) {
            this.product = product;
        }

        private void accept(ReceiptItem item) {
            timesBought++;
            var receipt = item.getReceipt();
            lastBoughtAt = receipt.getIssuedAt() != null ? receipt.getIssuedAt() : receipt.getConfirmedAt();
            lastUnitPrice = item.getUnitPrice();
            lastCnpj = receipt.getCnpjEmitente();
            lastMarketName = receipt.getMarketName();
        }

        private HouseholdProductResponse toResponse(Map<String, String> overrides, String friendlyName) {
            var friendlyMarket = overrides.getOrDefault(lastCnpj, lastMarketName);
            return new HouseholdProductResponse(
                    product.getId(), product.getNormalizedName(), friendlyName, product.getBrand(),
                    product.getCategory() != null ? product.getCategory().name() : null,
                    timesBought, lastBoughtAt, lastUnitPrice, lastCnpj, lastMarketName, friendlyMarket);
        }
    }
}
