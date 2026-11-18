package com.relyon.economizaai.service.shopping;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.request.OptimizeShoppingListRequest;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse.MarketPlan;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse.PlanItem;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse.SkippedSaving;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse.UnpricedItem;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PRO-52 — given a shopping list, return the per-market grouping that minimizes total cost
 * within the shopper's route rules.
 *
 * <p>Every item starts at its cheapest known store. The route rules then trim the stops:
 * <ul>
 *   <li><b>maxDistanceKm</b> — stores farther than this from the shopper are not considered
 *       (a store with no known position is out too, since its distance can't be checked).
 *       Only applies when the shopper's position is known.</li>
 *   <li><b>maxStops</b> — while the plan has more stops than allowed, the stop that is cheapest
 *       to give up is dropped and its items move to the next cheapest remaining store.</li>
 *   <li><b>minSavingPerStop</b> — a stop that saves less than this versus buying its items at
 *       the other stops is dropped. A stop holding an item sold nowhere else in the plan stays.</li>
 * </ul>
 * With no rules the result is the plain "cheapest store per item" plan.
 *
 * <p>Price source priority (per item, per market):
 * <ol>
 *   <li><b>Local history</b> — household's own most-recent purchase of this
 *       product at that market. Most accurate signal because it's the price
 *       the household actually paid.</li>
 *   <li><b>Community index</b> — median observation per market from the
 *       collaborative panel, k-anon-protected. Used only when local
 *       history doesn't cover this market.</li>
 *   <li><b>Unpriced</b> — neither source has data within the rules → moved to the
 *       {@code unpriced} bucket. FE shows a "preço indisponível" badge.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListOptimizer {

    private static final String REASON_NO_PRICE = "no observed price (local or community)";
    private static final String REASON_OUTSIDE_RULES = "no observed price within the route rules";

    private final ReceiptItemRepository receiptItemRepository;
    private final PriceObservationRepository observationRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final ProductRepository productRepository;
    private final CollaborativeProperties properties;
    private final MarketNameService marketNameService;
    private final SubscriptionGateService subscriptionGate;
    private final HouseholdProductAliasService householdProductAliasService;
    private final MarketLocationRepository marketLocationRepository;
    private final ViewerLocationService viewerLocationService;

    @Transactional(readOnly = true)
    public ShoppingPlanResponse optimize(User user, OptimizeShoppingListRequest request) {
        subscriptionGate.require(user, Feature.BASKET_OPTIMIZATION);
        var householdId = user.getHousehold().getId();
        var friendlyNames = householdProductAliasService.friendlyNamesFor(householdId,
                request.items().stream().map(requested -> requested.productId()).distinct().toList());
        var requestedItems = request.items().stream()
                .map(requested -> toRequestedItem(requested, householdId, friendlyNames))
                .toList();

        var origin = viewerLocationService.resolve(user, request.latitude(), request.longitude());
        var stores = storePositions(requestedItems, origin);
        var allowedStores = storesWithinDistance(stores, request.maxDistanceKm(), origin);
        var chosenStores = chooseStops(requestedItems, allowedStores, request.maxStops(), request.minSavingPerStop());

        var plan = buildPlan(requestedItems, chosenStores, allowedStores, stores, origin);
        var named = applyFriendlyMarketNames(householdId, plan);
        log.info("shopping_list.optimize household={} requested={} markets={} unpriced={} skipped_savings={} "
                        + "estimated_total={} max_distance_km={} max_stops={} min_saving={} origin={}",
                householdId, request.items().size(), named.marketPlans().size(), named.unpriced().size(),
                named.skippedSavings().size(), named.estimatedTotal(), request.maxDistanceKm(),
                request.maxStops(), request.minSavingPerStop(), origin.source());
        return named;
    }

    private RequestedItem toRequestedItem(OptimizeShoppingListRequest.Item requested, UUID householdId,
                                          Map<UUID, String> friendlyNames) {
        var product = productRepository.findById(requested.productId())
                .orElseThrow(ProductNotFoundException::new);
        return new RequestedItem(product, friendlyNames.get(product.getId()), requested.quantity(),
                candidatesByMarket(product, householdId));
    }

    /** Position and distance of every store that prices at least one requested item. */
    private Map<String, StorePosition> storePositions(List<RequestedItem> requestedItems, ViewerLocation origin) {
        var cnpjs = requestedItems.stream()
                .flatMap(item -> item.candidates().keySet().stream())
                .distinct()
                .toList();
        if (cnpjs.isEmpty()) return Map.of();
        return marketLocationRepository.findAllByCnpjIn(cnpjs).stream()
                .filter(MarketLocation::hasCoordinates)
                .collect(Collectors.toMap(MarketLocation::getCnpj,
                        location -> toStorePosition(location, origin), (first, ignored) -> first));
    }

    private StorePosition toStorePosition(MarketLocation location, ViewerLocation origin) {
        Double distanceKm = origin.hasCoordinates()
                ? DistanceCalculator.kmBetween(origin.latitude(), origin.longitude(),
                        location.getLatitude(), location.getLongitude())
                : null;
        return new StorePosition(location.getLatitude(), location.getLongitude(), distanceKm);
    }

    /**
     * The stores the plan may use. Null means "any store": no distance rule, or no known
     * position to measure from.
     */
    private Set<String> storesWithinDistance(Map<String, StorePosition> stores, BigDecimal maxDistanceKm,
                                             ViewerLocation origin) {
        if (maxDistanceKm == null || !origin.hasCoordinates()) return null;
        return stores.entrySet().stream()
                .filter(store -> store.getValue().distanceKm() <= maxDistanceKm.doubleValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Start from every store that is the cheapest for some item, then drop stops until the
     * plan respects {@code maxStops} and every remaining stop is worth {@code minSavingPerStop}.
     */
    private Set<String> chooseStops(List<RequestedItem> requestedItems, Set<String> allowedStores,
                                    Integer maxStops, BigDecimal minSavingPerStop) {
        var stops = new HashSet<String>();
        requestedItems.forEach(item -> item.cheapestAmong(allowedStores)
                .ifPresent(candidate -> stops.add(candidate.cnpj())));
        while (stops.size() > 1) {
            var overLimit = maxStops != null && stops.size() > maxStops;
            var stopToDrop = overLimit
                    ? cheapestStopToGiveUp(requestedItems, stops)
                    : stopNotWorthTheTrip(requestedItems, stops, minSavingPerStop);
            if (stopToDrop == null) break;
            stops.remove(stopToDrop);
        }
        return stops;
    }

    /** Over the stop limit: give up the stop that strands the fewest items, then costs the least. */
    private String cheapestStopToGiveUp(List<RequestedItem> requestedItems, Set<String> stops) {
        return stops.stream()
                .map(stop -> costOfDropping(requestedItems, stops, stop))
                .min(Comparator.comparing(DropCost::strandedItems)
                        .thenComparing(DropCost::extraCost)
                        .thenComparing(DropCost::cnpj))
                .map(DropCost::cnpj)
                .orElse(null);
    }

    /** The stop that saves the least, when that saving is below the minimum; null if every stop pays off. */
    private String stopNotWorthTheTrip(List<RequestedItem> requestedItems, Set<String> stops,
                                       BigDecimal minSavingPerStop) {
        if (minSavingPerStop == null) return null;
        return stops.stream()
                .map(stop -> costOfDropping(requestedItems, stops, stop))
                .filter(dropCost -> dropCost.strandedItems() == 0)
                .filter(dropCost -> dropCost.extraCost().compareTo(minSavingPerStop) < 0)
                .min(Comparator.comparing(DropCost::extraCost).thenComparing(DropCost::cnpj))
                .map(DropCost::cnpj)
                .orElse(null);
    }

    /** What the plan loses without {@code stop}: how much more it costs and how many items end up with no store. */
    private DropCost costOfDropping(List<RequestedItem> requestedItems, Set<String> stops, String stop) {
        var remainingStops = new HashSet<>(stops);
        remainingStops.remove(stop);
        var extraCost = BigDecimal.ZERO;
        var strandedItems = 0;
        for (var item : requestedItems) {
            var current = item.cheapestAmong(stops).orElse(null);
            if (current == null || !current.cnpj().equals(stop)) continue;
            var alternative = item.cheapestAmong(remainingStops).orElse(null);
            if (alternative == null) {
                strandedItems++;
            } else {
                extraCost = extraCost.add(item.subtotalAt(alternative).subtract(item.subtotalAt(current)));
            }
        }
        return new DropCost(stop, extraCost, strandedItems);
    }

    private ShoppingPlanResponse buildPlan(List<RequestedItem> requestedItems, Set<String> chosenStores,
                                           Set<String> allowedStores, Map<String, StorePosition> stores,
                                           ViewerLocation origin) {
        var perMarket = new LinkedHashMap<String, MarketPlanBuilder>();
        var unpriced = new ArrayList<UnpricedItem>();
        var skippedSavings = new ArrayList<SkippedSaving>();
        var totalCost = BigDecimal.ZERO;

        for (var item : requestedItems) {
            var chosen = item.cheapestAmong(chosenStores).orElse(null);
            if (chosen == null) {
                unpriced.add(item.toUnpriced(item.candidates().isEmpty() ? REASON_NO_PRICE : REASON_OUTSIDE_RULES));
                continue;
            }
            var planItem = item.toPlanItem(chosen);
            perMarket.computeIfAbsent(chosen.cnpj(), cnpj -> new MarketPlanBuilder(chosen.cnpj(), chosen.marketName()))
                    .add(planItem);
            totalCost = totalCost.add(planItem.estimatedSubtotal());
            item.cheapestAmong(allowedStores)
                    .filter(cheapest -> item.subtotalAt(cheapest).compareTo(planItem.estimatedSubtotal()) < 0)
                    .ifPresent(cheapest -> skippedSavings.add(item.toSkippedSaving(cheapest,
                            planItem.estimatedSubtotal().subtract(item.subtotalAt(cheapest)))));
        }

        var marketPlans = perMarket.values().stream()
                .map(builder -> builder.build(stores.get(builder.cnpj)))
                .sorted(routeOrder(origin))
                .toList();
        skippedSavings.sort(Comparator.comparing(SkippedSaving::saving).reversed());
        return new ShoppingPlanResponse(marketPlans, totalCost, unpriced, skippedSavings);
    }

    /** Nearest stop first when the shopper's position is known; otherwise largest subtotal first. */
    private Comparator<MarketPlan> routeOrder(ViewerLocation origin) {
        var bySubtotal = Comparator.comparing(MarketPlan::subtotal).reversed();
        if (!origin.hasCoordinates()) return bySubtotal;
        return Comparator.comparing(MarketPlan::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(bySubtotal);
    }

    private ShoppingPlanResponse applyFriendlyMarketNames(UUID householdId, ShoppingPlanResponse plan) {
        var cnpjs = Stream.concat(
                        plan.marketPlans().stream().map(MarketPlan::marketCnpj),
                        plan.skippedSavings().stream().map(SkippedSaving::marketCnpj))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var overrides = marketNameService.resolveNames(householdId, cnpjs);
        var marketPlans = plan.marketPlans().stream()
                .map(marketPlan -> marketPlan.withMarketFriendlyName(marketNameService.applyOverride(
                        overrides, marketPlan.marketCnpj(), marketPlan.marketName())))
                .toList();
        var skippedSavings = plan.skippedSavings().stream()
                .map(skipped -> skipped.withMarketFriendlyName(marketNameService.applyOverride(
                        overrides, skipped.marketCnpj(), skipped.marketName())))
                .toList();
        return new ShoppingPlanResponse(marketPlans, plan.estimatedTotal(), plan.unpriced(), skippedSavings);
    }

    /** Every market that prices this product for the household: own history first, community median as fallback. */
    private Map<String, MarketCandidate> candidatesByMarket(Product product, UUID householdId) {
        // Local history first — most recent purchase at each market the household has been to.
        var candidatesByMarket = receiptItemRepository
                .findHouseholdHistoryForProduct(product.getId(), householdId).stream()
                .filter(item -> item.getReceipt().getCnpjEmitente() != null && item.getUnitPrice() != null)
                .collect(Collectors.toMap(
                        item -> item.getReceipt().getCnpjEmitente(),
                        item -> new MarketCandidate(item.getReceipt().getCnpjEmitente(),
                                item.getReceipt().getMarketName(), item.getUnitPrice(),
                                PlanItem.PriceSource.LOCAL_HISTORY),
                        // History comes oldest-first, so the later row is the most recent purchase.
                        (older, newer) -> newer));

        // Community panel as fallback — median per market that local history misses.
        if (properties.getCollaborative().isEnabled()) {
            var since = BrazilClock.nowDateTime().minusDays(properties.getCollaborative().getLookbackDays());
            var observations = observationRepository.findRecentByProduct(product.getId(), since);
            var byMarket = observations.stream()
                    .collect(Collectors.groupingBy(observation -> observation.getMarketCnpj()));
            for (var entry : byMarket.entrySet()) {
                if (candidatesByMarket.containsKey(entry.getKey())) continue;
                var rows = entry.getValue();
                if (rows.size() < properties.getCollaborative().getMinObservationsPerProductMarket()) continue;
                var distinct = auditRepository.countDistinctHouseholdsForProductMarket(
                        product.getId(), entry.getKey(), since);
                if (distinct < properties.getCollaborative().getMinHouseholdsForPublic()) continue;
                var prices = rows.stream().map(observation -> observation.getUnitPrice()).sorted().toList();
                var median = prices.get(prices.size() / 2);
                candidatesByMarket.put(entry.getKey(),
                        new MarketCandidate(entry.getKey(), rows.get(0).getMarketName(), median,
                                PlanItem.PriceSource.COMMUNITY_INDEX));
            }
        }
        return candidatesByMarket;
    }

    private record MarketCandidate(String cnpj, String marketName, BigDecimal unitPrice,
                                   PlanItem.PriceSource source) {}

    private record StorePosition(BigDecimal latitude, BigDecimal longitude, Double distanceKm) {}

    private record DropCost(String cnpj, BigDecimal extraCost, int strandedItems) {}

    private record RequestedItem(Product product, String friendlyDescription, BigDecimal quantity,
                                 Map<String, MarketCandidate> candidates) {

        /** Cheapest candidate among {@code stores}; a null set means any store. */
        Optional<MarketCandidate> cheapestAmong(Set<String> stores) {
            return candidates.values().stream()
                    .filter(candidate -> stores == null || stores.contains(candidate.cnpj()))
                    .min(Comparator.comparing(MarketCandidate::unitPrice).thenComparing(MarketCandidate::cnpj));
        }

        BigDecimal subtotalAt(MarketCandidate candidate) {
            return candidate.unitPrice().multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        }

        PlanItem toPlanItem(MarketCandidate candidate) {
            return new PlanItem(product.getId(), product.getNormalizedName(), friendlyDescription,
                    quantity, candidate.unitPrice(), subtotalAt(candidate), candidate.source());
        }

        UnpricedItem toUnpriced(String reason) {
            return new UnpricedItem(product.getId(), product.getNormalizedName(), friendlyDescription,
                    quantity, reason);
        }

        SkippedSaving toSkippedSaving(MarketCandidate cheapest, BigDecimal saving) {
            return new SkippedSaving(product.getId(), product.getNormalizedName(), friendlyDescription,
                    cheapest.cnpj(), cheapest.marketName(), cheapest.marketName(), saving);
        }
    }

    private static final class MarketPlanBuilder {
        private final String cnpj;
        private final String name;
        private final List<PlanItem> items = new ArrayList<>();
        private BigDecimal subtotal = BigDecimal.ZERO;

        MarketPlanBuilder(String cnpj, String name) {
            this.cnpj = cnpj;
            this.name = name;
        }

        void add(PlanItem item) {
            items.add(item);
            subtotal = subtotal.add(item.estimatedSubtotal());
        }

        MarketPlan build(StorePosition position) {
            if (position == null) return new MarketPlan(cnpj, name, name, subtotal, items.size(), items);
            return new MarketPlan(cnpj, name, name, subtotal, items.size(), items,
                    position.latitude(), position.longitude(), position.distanceKm());
        }
    }
}
