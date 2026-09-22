package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.notifications.DealFeedbackService;
import com.relyon.economizaai.service.notifications.RelevanceThreshold;
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
import java.util.Set;
import java.util.UUID;

/**
 * Builds the "Ofertas pra você" screen (Phase B of the notifications overhaul):
 * an active, ranked query of the discounts currently relevant to the
 * authenticated user's household.
 *
 * <p>For every product the household has actually bought (its purchase history),
 * we ask {@link PriceIndexService#bestMarkets} for the best community price at a
 * relevant market — watched markets always, nearby markets only when the caller
 * opts in via {@code includeNearby}. The community median is k-anonymity guarded
 * inside {@code bestMarkets} (disclosed only when ≥ K distinct households
 * contributed), so no sub-K price ever leaks here. We keep the cheapest market
 * whose price beats the household's last-paid by at least the progressive
 * {@link RelevanceThreshold#requiredDropFraction(BigDecimal)} bar, then rank by
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
    private final WatchedMarketService watchedMarketService;
    private final MarketNameService marketNameService;
    private final DealFeedbackService dealFeedbackService;
    private final HouseholdProductAliasService householdProductAliasService;

    @Transactional(readOnly = true)
    public List<DealResponse> findDeals(User user, boolean includeNearby, Double radiusKm, int limit) {
        if (!properties.getCollaborative().isEnabled() || limit <= 0) return List.of();

        var householdId = user.getHousehold().getId();
        var history = receiptItemRepository.findConfirmedHistoryForHousehold(householdId);
        var purchased = aggregateByProduct(history);
        if (purchased.isEmpty()) return List.of();

        var relevanceMode = dealFeedbackService.mode();
        var suppressions = dealFeedbackService.suppressionsFor(user);
        var watchedCnpjs = watchedMarketService.watchedCnpjs(user);
        var friendlyNames = householdProductAliasService.friendlyNamesFor(householdId,
                List.copyOf(purchased.keySet()));
        var deals = new ArrayList<DealResponse>();
        var suppressedCount = 0;
        for (var aggregate : purchased.values()) {
            // ON-mode shortcut: a product muted/dismissed market-wide can't yield
            // a visible deal, so skip the (expensive) bestMarkets lookup outright.
            if (relevanceMode == RelevanceMode.ON
                    && suppressions.suppressesProduct(aggregate.product.getId())) {
                suppressedCount++;
                continue;
            }
            var deal = bestDealForProduct(user, aggregate, watchedCnpjs, includeNearby, radiusKm,
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
        log.info("deals.query done household={} candidates={} qualified={} returned={}",
                householdId, purchased.size(), deals.size(), ranked.size());
        return ranked;
    }

    /**
     * Cheapest qualifying community market for one product the household buys, or
     * null when nothing currently beats their last-paid by the relevance threshold.
     */
    private DealResponse bestDealForProduct(User user, ProductAggregate aggregate, Set<String> watchedCnpjs,
                                            boolean includeNearby, Double radiusKm, String friendlyDescription) {
        var lastPaid = aggregate.lastUnitPrice;
        if (lastPaid == null || lastPaid.signum() <= 0) return null;

        var markets = priceIndexService.bestMarkets(aggregate.product.getId(), MARKET_LIMIT,
                user.getHomeLatitude(), user.getHomeLongitude(),
                includeNearby ? radiusKm : null, watchedCnpjs);
        if (markets.isEmpty()) return null;

        var best = cheapestQualifyingMarket(markets, lastPaid);
        if (best == null) return null;

        return buildDeal(user, aggregate, best, lastPaid, friendlyDescription);
    }

    /**
     * The genuinely cheapest market whose median beats last-paid by the
     * progressive relevance threshold, or null if none qualify. bestMarkets
     * returns cheapest-median first within (watched, median) ordering, but
     * watched markets sort ahead regardless of price — so we scan for the
     * cheapest qualifying market rather than trusting position 0.
     */
    private PriceIndexService.MarketPriceRow cheapestQualifyingMarket(
            List<PriceIndexService.MarketPriceRow> markets, BigDecimal lastPaid) {
        var requiredDrop = RelevanceThreshold.requiredDropFraction(lastPaid);
        var threshold = lastPaid.multiply(BigDecimal.valueOf(1.0 - requiredDrop));
        return markets.stream()
                .filter(market -> market.medianPrice() != null)
                .filter(market -> market.medianPrice().compareTo(threshold) <= 0)
                .min(Comparator.comparing(PriceIndexService.MarketPriceRow::medianPrice))
                .orElse(null);
    }

    /** Compute savings (amount, fraction, pct) against last-paid and assemble the response row. */
    private DealResponse buildDeal(User user, ProductAggregate aggregate,
                                   PriceIndexService.MarketPriceRow best, BigDecimal lastPaid,
                                   String friendlyDescription) {
        var currentPrice = best.medianPrice().setScale(2, RoundingMode.HALF_UP);
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
                aggregate.lastBoughtAt);
    }

    /** Collapse the household's confirmed history (oldest -> newest) into one row per
     *  product: last-paid price, last-bought timestamp, and purchase frequency. */
    private LinkedHashMap<UUID, ProductAggregate> aggregateByProduct(List<ReceiptItem> history) {
        var byProduct = new LinkedHashMap<UUID, ProductAggregate>();
        for (var item : history) {
            var product = item.getProduct();
            if (product == null) continue;
            byProduct.computeIfAbsent(product.getId(), key -> new ProductAggregate(product)).accept(item);
        }
        return byProduct;
    }

    /** Per-product accumulator over the household's purchase history (fed oldest -> newest). */
    private static final class ProductAggregate {
        private final Product product;
        private long timesBought;
        private BigDecimal lastUnitPrice;
        private LocalDateTime lastBoughtAt;

        private ProductAggregate(Product product) {
            this.product = product;
        }

        private void accept(ReceiptItem item) {
            timesBought++;
            if (item.getUnitPrice() != null) lastUnitPrice = item.getUnitPrice();
            var receipt = item.getReceipt();
            lastBoughtAt = receipt.getIssuedAt() != null ? receipt.getIssuedAt() : receipt.getConfirmedAt();
        }
    }
}
