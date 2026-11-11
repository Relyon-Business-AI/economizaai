package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DealPriceSource;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.NearbyScope;
import com.relyon.economizaai.service.geo.NearbyScopeService;
import com.relyon.economizaai.service.notifications.DealFeedbackService;
import com.relyon.economizaai.service.notifications.RelevanceThreshold;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the "Ofertas pra você" screen (Phase B of the notifications overhaul):
 * an active, ranked query of the discounts currently relevant to the
 * authenticated user's household.
 *
 * <p>For every product the household has actually bought (its purchase history),
 * we ask the price index for the chains selling it at a relevant market:
 * {@code includeNearby=false} = only the markets the user follows ("Mercados que sigo");
 * {@code includeNearby=true} = the "perto de você" {@link NearbyScope} cascade (followed +
 * within the radius of the explicit/inferred location, else UF, else national). Never a
 * market across the country. Rules of a deal:
 * <ul>
 *   <li>its price is the chain's MOST RECENT observed price, at most
 *       {@code deals-max-price-age-days} (60) old — the number the best-markets screen shows;</li>
 *   <li>the chain where the household made its LAST purchase of the product is never
 *       suggested (that is the price being compared against);</li>
 *   <li>the household's own earlier purchases count as evidence, labelled
 *       {@link DealPriceSource#OWN_PURCHASE} ("você pagou menos no mercado A");</li>
 *   <li>k-anonymity is enforced by tier inside {@code bestMarkets}, so no sub-K price
 *       ever leaks here.</li>
 * </ul>
 * We keep the cheapest chain whose price beats the household's last-paid by at least the
 * progressive {@link RelevanceThreshold#requiredDropFraction(BigDecimal)} bar, then rank by
 * savings (discount fraction) desc, tie-broken by how often the household buys it.
 *
 * <p>This endpoint never emits telemetry — the app reports
 * SCREEN_OPENED / DEAL_VIEWED / DEAL_TAPPED itself via the Phase A events API.
 *
 * <p>Query cost: one history query for the household, then one
 * {@code bestMarkets} lookup per distinct product bought. On-demand, single-user,
 * so moderate N is acceptable; last-paid + frequency come from the single history
 * query (no per-product round trip for those).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DealsService {

    /** How many markets bestMarkets considers per product before we pick the cheapest. */
    private static final int MARKET_LIMIT = 50;

    private final ReceiptItemRepository receiptItemRepository;
    private final PriceIndexService priceIndexService;
    private final CollaborativeProperties properties;
    private final NearbyScopeService nearbyScopeService;
    private final MarketNameService marketNameService;
    private final DealFeedbackService dealFeedbackService;
    private final HouseholdProductAliasService householdProductAliasService;

    @Transactional(readOnly = true)
    public List<DealResponse> findDeals(User user, boolean includeNearby, Double radiusKm, int limit) {
        return findDeals(user, includeNearby, radiusKm, limit, null, null);
    }

    /** Same, with the device's live coordinates (both or neither) leading the location cascade. */
    @Transactional(readOnly = true)
    public List<DealResponse> findDeals(User user, boolean includeNearby, Double radiusKm, int limit,
                                        BigDecimal deviceLatitude, BigDecimal deviceLongitude) {
        if (!properties.getCollaborative().isEnabled() || limit <= 0) return List.of();

        var householdId = user.getHousehold().getId();
        var history = receiptItemRepository.findConfirmedHistoryForHousehold(householdId);
        var purchased = aggregateByProduct(history);
        if (purchased.isEmpty()) return List.of();

        var relevanceMode = dealFeedbackService.mode();
        var suppressions = dealFeedbackService.suppressionsFor(user);
        var scope = nearbyScopeService.forUser(user, radiusKm, deviceLatitude, deviceLongitude);
        var friendlyNames = householdProductAliasService.friendlyNamesFor(householdId,
                List.copyOf(purchased.keySet()));
        var lowestPrices = priceIndexService.lowestRecentInStorePrices(purchased.keySet());
        var deals = new ArrayList<DealResponse>();
        var suppressedCount = 0;
        var skippedNoCheaperPrice = 0;
        for (var aggregate : purchased.values()) {
            if (!canBeatLastPaid(aggregate, lowestPrices)) {
                skippedNoCheaperPrice++;
                continue;
            }
            // ON-mode shortcut: a product muted/dismissed market-wide can't yield
            // a visible deal, so skip the (expensive) bestMarkets lookup outright.
            if (relevanceMode == RelevanceMode.ON
                    && suppressions.suppressesProduct(aggregate.product.getId())) {
                suppressedCount++;
                continue;
            }
            var deal = bestDealForProduct(user, aggregate, scope, includeNearby,
                    friendlyNames.get(aggregate.product.getId()));
            if (deal == null) continue;
            if (suppressions.suppresses(deal.productId(), deal.marketCnpj())) {
                suppressedCount++;
                if (relevanceMode == RelevanceMode.ON) continue;
                // SHADOW: counted for the relevance report, but still shown.
            }
            deals.add(deal);
        }
        if (suppressedCount > 0) {
            log.info("relevance.{} suppressed={} candidates={}",
                    relevanceMode == RelevanceMode.ON ? "applied" : "shadow",
                    suppressedCount, purchased.size());
        }

        var ranked = deals.stream()
                .sorted(Comparator
                        .comparing(DealResponse::discountFraction, Comparator.reverseOrder())
                        .thenComparing(deal -> purchased.get(deal.productId()).timesBought,
                                Comparator.reverseOrder())
                        .thenComparing(DealResponse::observedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
        log.info("deals.query done household={} scope={} candidates={} skipped_no_cheaper={} qualified={} returned={}",
                householdId, includeNearby ? scope.tier() : "FOLLOWED", purchased.size(), skippedNoCheaperPrice,
                deals.size(), ranked.size());
        return ranked;
    }

    /**
     * Cheapest qualifying chain for one product the household buys, or null when
     * nothing currently beats their last-paid by the relevance threshold.
     */
    private DealResponse bestDealForProduct(User user, ProductAggregate aggregate, NearbyScope scope,
                                            boolean includeNearby, String friendlyDescription) {
        var lastPaid = aggregate.lastUnitPrice;
        if (lastPaid == null || lastPaid.signum() <= 0) return null;

        var productId = aggregate.product.getId();
        var markets = includeNearby
                ? priceIndexService.bestMarketsInScope(productId, MARKET_LIMIT, scope)
                : priceIndexService.bestMarketsAtWatched(productId, MARKET_LIMIT, scope.watchedCnpjs());
        if (markets.isEmpty()) return null;

        var best = cheapestQualifyingMarket(markets, aggregate);
        if (best == null) return null;

        return buildDeal(user, aggregate, best, lastPaid, friendlyDescription);
    }

    /**
     * The cheapest chain — by its latest observed price — that (a) was observed recently
     * enough, (b) is not the chain of the household's last purchase, and (c) beats last-paid
     * by the progressive relevance threshold. Null if none qualify. bestMarkets sorts watched
     * chains ahead regardless of price, so we scan rather than trusting position 0.
     */
    private PriceIndexService.MarketPriceRow cheapestQualifyingMarket(
            List<PriceIndexService.MarketPriceRow> markets, ProductAggregate aggregate) {
        var ceiling = qualifyingPriceCeiling(aggregate.lastUnitPrice);
        var oldestAcceptedDay = oldestAcceptedObservationDay();
        return markets.stream()
                .filter(market -> market.latestPrice() != null && market.latestObservedAt() != null)
                .filter(market -> !market.latestObservedAt().isBefore(oldestAcceptedDay))
                .filter(market -> !isChainOfLastPurchase(market, aggregate))
                .filter(market -> market.latestPrice().compareTo(ceiling) <= 0)
                .min(Comparator.comparing(PriceIndexService.MarketPriceRow::latestPrice))
                .orElse(null);
    }

    /** Observations carry day precision, so the window is compared day to day. */
    private LocalDateTime oldestAcceptedObservationDay() {
        return BrazilClock.nowDateTime()
                .minusDays(properties.getCollaborative().getDealsMaxPriceAgeDays())
                .toLocalDate().atStartOfDay();
    }

    private static boolean isChainOfLastPurchase(PriceIndexService.MarketPriceRow market,
                                                 ProductAggregate aggregate) {
        return aggregate.lastChainRoot != null && aggregate.lastChainRoot.equals(market.cnpjRoot());
    }

    /**
     * Cheap pre-check before the per-product market ranking: a chain's latest price is never
     * below the product's lowest recent observation, so if even that lowest price doesn't reach
     * the qualifying ceiling, no market can produce a deal and the (expensive) ranking is skipped.
     */
    private boolean canBeatLastPaid(ProductAggregate aggregate, Map<UUID, BigDecimal> lowestPrices) {
        var lastPaid = aggregate.lastUnitPrice;
        if (lastPaid == null || lastPaid.signum() <= 0) return false;
        var lowest = lowestPrices.get(aggregate.product.getId());
        return lowest != null && lowest.compareTo(qualifyingPriceCeiling(lastPaid)) <= 0;
    }

    /** Highest price that still beats last-paid by the progressive relevance threshold. */
    private BigDecimal qualifyingPriceCeiling(BigDecimal lastPaid) {
        var requiredDrop = RelevanceThreshold.requiredDropFraction(lastPaid);
        return lastPaid.multiply(BigDecimal.valueOf(1.0 - requiredDrop));
    }

    /** Compute savings (amount, fraction, pct) against last-paid and assemble the response row. */
    private DealResponse buildDeal(User user, ProductAggregate aggregate,
                                   PriceIndexService.MarketPriceRow best, BigDecimal lastPaid,
                                   String friendlyDescription) {
        var currentPrice = best.latestPrice().setScale(2, RoundingMode.HALF_UP);
        var savingsAmount = lastPaid.subtract(currentPrice).setScale(2, RoundingMode.HALF_UP);
        var discountFraction = lastPaid.subtract(currentPrice)
                .divide(lastPaid, 4, RoundingMode.HALF_UP);
        var savingsPct = discountFraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        var friendlyName = marketNameService.resolve(
                user.getHousehold().getId(), best.cnpj(), best.marketName());

        return new DealResponse(
                aggregate.product.getId(),
                aggregate.product.getNormalizedName(),
                friendlyDescription,
                aggregate.product.getCategory() != null ? aggregate.product.getCategory().name() : null,
                best.cnpj(),
                friendlyName,
                currentPrice,
                lastPaid,
                savingsAmount,
                savingsPct,
                discountFraction,
                best.distinctHouseholds(),
                best.distanceKm(),
                best.watching(),
                best.latestObservedAt(),
                aggregate.boughtAt(best) ? DealPriceSource.OWN_PURCHASE : DealPriceSource.COMMUNITY);
    }

    /** Collapse the household's confirmed history (oldest -> newest) into one row per
     *  product: last-paid price, the chain it was last bought at, and purchase frequency. */
    private LinkedHashMap<UUID, ProductAggregate> aggregateByProduct(List<ReceiptItem> history) {
        var byProduct = new LinkedHashMap<UUID, ProductAggregate>();
        for (var item : history) {
            var product = item.getProduct();
            if (product == null) continue;
            byProduct.computeIfAbsent(product.getId(), key -> new ProductAggregate(product)).accept(item);
        }
        return byProduct;
    }

    /** One priced purchase of the product by the household: where, which day, at what unit price. */
    private record OwnPurchase(String chainRoot, LocalDate day, BigDecimal unitPrice) {}

    /** Per-product accumulator over the household's purchase history (fed oldest -> newest). */
    private static final class ProductAggregate {
        private final Product product;
        private final List<OwnPurchase> pricedPurchases = new ArrayList<>();
        private long timesBought;
        private BigDecimal lastUnitPrice;
        private String lastChainRoot;

        private ProductAggregate(Product product) {
            this.product = product;
        }

        private void accept(ReceiptItem item) {
            timesBought++;
            if (item.getUnitPrice() == null) return;
            var receipt = item.getReceipt();
            lastUnitPrice = item.getUnitPrice();
            lastChainRoot = PriceIndexService.cnpjRoot(receipt.getCnpjEmitente());
            var boughtAt = receipt.getIssuedAt() != null ? receipt.getIssuedAt() : receipt.getConfirmedAt();
            if (lastChainRoot != null && boughtAt != null) {
                pricedPurchases.add(new OwnPurchase(lastChainRoot, boughtAt.toLocalDate(), item.getUnitPrice()));
            }
        }

        /**
         * Whether the observation pricing this chain is the household's own purchase.
         * Observations are anonymous by design (no household id), so "own" is derived from the
         * household's receipts: same product, same chain, same day and same unit price.
         */
        private boolean boughtAt(PriceIndexService.MarketPriceRow market) {
            var observedDay = market.latestObservedAt().toLocalDate();
            return pricedPurchases.stream().anyMatch(purchase ->
                    purchase.chainRoot().equals(market.cnpjRoot())
                            && purchase.day().equals(observedDay)
                            && purchase.unitPrice().compareTo(market.latestPrice()) == 0);
        }
    }
}
