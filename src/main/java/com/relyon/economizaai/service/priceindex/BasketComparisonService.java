package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.priceindex.PriceIndexService.MarketPriceRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "How much would this basket cost at another market?" — prices a set of bought items at each
 * nearby chain and returns the chains that cover the most of it.
 *
 * <p>Only items the other market has a price for are compared, and each alternative carries the
 * basket's own total for those SAME items, so the two numbers are always like for like. A market
 * needs to price at least {@link #MIN_MATCHED_ITEMS} items (or the whole basket, when smaller) to
 * be offered: a comparison over one or two items says nothing about the whole purchase.
 *
 * <p>Prices come from {@link PriceIndexService#bestMarketsForViewer}, so the "perto de você"
 * radius and the k-anonymity gates are exactly the ones of the best-markets screen. Without a
 * known position there is no "nearby", and the result is empty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BasketComparisonService {

    /** Fewest items a market must price for the comparison to mean something. */
    static final int MIN_MATCHED_ITEMS = 3;
    /** Chains considered per product — far above what a 20 km radius holds. */
    private static final int MAX_CHAINS_PER_PRODUCT = 100;

    private final PriceIndexService priceIndexService;

    /** One bought product: how much of it and what the whole line cost. */
    public record BasketLine(UUID productId, String productName, BigDecimal quantity, BigDecimal lineTotal) {}

    /** One basket line priced at the alternative market. */
    public record AlternativeLine(UUID productId, String productName, BigDecimal quantity,
                                  BigDecimal basketLineTotal, BigDecimal alternativeUnitPrice,
                                  BigDecimal alternativeLineTotal, LocalDateTime observedAt) {}

    /**
     * The basket at one chain, shown as its nearest store. {@code basketTotal} and
     * {@code alternativeTotal} cover only {@code lines} (the items this chain prices).
     */
    public record MarketAlternative(String cnpj, String cnpjRoot, String marketName, Double distanceKm,
                                    BigDecimal basketTotal, BigDecimal alternativeTotal,
                                    List<AlternativeLine> lines) {
        public int matchedItemCount() {
            return lines.size();
        }

        /** Positive = the alternative is more expensive; negative = it would have been cheaper. */
        public BigDecimal difference() {
            return alternativeTotal.subtract(basketTotal);
        }
    }

    /**
     * Up to {@code maxMarkets} nearby chains for the basket, the ones covering the most items first
     * (nearest first on a tie). Chains in {@code excludedCnpjRoots} (where the basket was bought)
     * are left out.
     */
    @Transactional(readOnly = true)
    public List<MarketAlternative> compare(ViewerLocation location, Collection<BasketLine> basketLines,
                                           Set<String> excludedCnpjRoots, int maxMarkets) {
        if (!location.hasCoordinates() || basketLines.isEmpty() || maxMarkets <= 0) return List.of();
        var basket = mergeByProduct(basketLines);
        var chains = new LinkedHashMap<String, ChainBasket>();
        for (var line : basket) {
            priceIndexService.bestMarketsForViewer(line.productId(), MAX_CHAINS_PER_PRODUCT, location, Set.of())
                    .stream()
                    .filter(row -> row.latestPrice() != null && row.cnpjRoot() != null)
                    .filter(row -> !excludedCnpjRoots.contains(row.cnpjRoot()))
                    .forEach(row -> chains.computeIfAbsent(row.cnpjRoot(), ChainBasket::new).add(line, row));
        }
        var minMatchedItems = Math.min(MIN_MATCHED_ITEMS, basket.size());
        var alternatives = chains.values().stream()
                .filter(chain -> chain.lines.size() >= minMatchedItems)
                .map(ChainBasket::toAlternative)
                .sorted(Comparator.comparing(MarketAlternative::matchedItemCount).reversed()
                        .thenComparing(MarketAlternative::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MarketAlternative::cnpjRoot))
                .limit(maxMarkets)
                .toList();
        log.debug("basket_comparison products={} chains_seen={} offered={}", basket.size(), chains.size(),
                alternatives.size());
        return alternatives;
    }

    /** One line per product: the same product on two lines of a nota is one item to compare. */
    private List<BasketLine> mergeByProduct(Collection<BasketLine> basketLines) {
        var merged = new LinkedHashMap<UUID, BasketLine>();
        for (var line : basketLines) {
            merged.merge(line.productId(), line, (first, second) -> new BasketLine(first.productId(),
                    first.productName(), first.quantity().add(second.quantity()),
                    first.lineTotal().add(second.lineTotal())));
        }
        return List.copyOf(merged.values());
    }

    /** The basket lines one chain prices, plus the store of the chain nearest to the viewer. */
    private static final class ChainBasket {
        private final String cnpjRoot;
        private final List<AlternativeLine> lines = new ArrayList<>();
        private MarketPriceRow nearestStore;

        ChainBasket(String cnpjRoot) {
            this.cnpjRoot = cnpjRoot;
        }

        void add(BasketLine line, MarketPriceRow row) {
            var alternativeLineTotal = row.latestPrice().multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP);
            lines.add(new AlternativeLine(line.productId(), line.productName(), line.quantity(),
                    line.lineTotal().setScale(2, RoundingMode.HALF_UP), row.latestPrice(), alternativeLineTotal,
                    row.latestObservedAt()));
            if (nearestStore == null || isNearer(row, nearestStore)) nearestStore = row;
        }

        private static boolean isNearer(MarketPriceRow candidate, MarketPriceRow current) {
            return candidate.distanceKm() != null
                    && (current.distanceKm() == null || candidate.distanceKm() < current.distanceKm());
        }

        MarketAlternative toAlternative() {
            var basketTotal = lines.stream().map(AlternativeLine::basketLineTotal)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            var alternativeTotal = lines.stream().map(AlternativeLine::alternativeLineTotal)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            return new MarketAlternative(nearestStore.cnpj(), cnpjRoot, nearestStore.marketName(),
                    nearestStore.distanceKm(), basketTotal, alternativeTotal, List.copyOf(lines));
        }
    }
}
