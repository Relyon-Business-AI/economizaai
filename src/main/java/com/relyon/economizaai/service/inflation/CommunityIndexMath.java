package com.relyon.economizaai.service.inflation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The arithmetic of the community price index: a matched-pair, chained index. A pair is one
 * product at one store on one unit basis; it links two months when it has a price in both, and
 * its relative (price this month / price last month) weighs by what was spent on it. Pure
 * functions — no database, no settings; callers pass the thresholds in.
 */
public final class CommunityIndexMath {

    /** Basis of a price that has no normalized (per kg / per litre) form: compared only with itself. */
    public static final String RAW_BASIS = "RAW";
    public static final double BASE_INDEX = 100.0;

    private CommunityIndexMath() {}

    /** One price paid: which product, where, on which unit basis, and the money spent on that line. */
    public record PriceSample(UUID productId, String store, String state, String basis, double price, double spend) {}

    /** The month's change of one product in one state, ready to be stored. */
    public record ProductChange(String state, UUID productId, double weightedLogSum, double weightSum, int pairCount) {}

    public record StateCoverage(String state, int pairCount, int storeCount) {}

    public record MonthChanges(List<ProductChange> productChanges, List<StateCoverage> coverage) {}

    /** A run of consecutive months, both ends included. */
    public record Span(YearMonth from, YearMonth to) {
        public List<YearMonth> months() {
            var months = new ArrayList<YearMonth>();
            for (var month = from; !month.isAfter(to); month = month.plusMonths(1)) months.add(month);
            return months;
        }

        public int length() {
            return months().size();
        }
    }

    public record IndexPoint(YearMonth month, double index) {}

    private record PairKey(String state, UUID productId, String store, String basis) {}

    private record PairMonth(double medianPrice, double spend) {}

    private static final class Accumulator {
        private double weightedLogSum;
        private double weightSum;
        private int pairCount;
    }

    /**
     * Matches the pairs priced in both months and adds them up per (state, product). A relative
     * beyond {@code maxMonthlyChangePercent} in either direction (more than ×(1+max) or less
     * than ÷(1+max)) is a unit or registration error and is dropped.
     */
    public static MonthChanges monthChanges(Collection<PriceSample> previousMonth, Collection<PriceSample> currentMonth,
                                            double maxMonthlyChangePercent) {
        var previousByPair = pairMonths(previousMonth);
        var currentByPair = pairMonths(currentMonth);
        var largestLogChange = Math.log(1 + maxMonthlyChangePercent / 100.0);

        var byStateProduct = new HashMap<String, Map<UUID, Accumulator>>();
        var pairsByState = new TreeMap<String, Integer>();
        var storesByState = new HashMap<String, Set<String>>();
        currentByPair.forEach((pair, current) -> {
            var previous = previousByPair.get(pair);
            if (previous == null || previous.medianPrice() <= 0 || current.medianPrice() <= 0) return;
            var logRelative = Math.log(current.medianPrice() / previous.medianPrice());
            if (Math.abs(logRelative) > largestLogChange + 1e-12) return;
            var weight = (current.spend() + previous.spend()) / 2;
            if (weight <= 0) return;

            var accumulator = byStateProduct.computeIfAbsent(pair.state(), state -> new HashMap<>())
                    .computeIfAbsent(pair.productId(), productId -> new Accumulator());
            accumulator.weightedLogSum += weight * logRelative;
            accumulator.weightSum += weight;
            accumulator.pairCount++;
            pairsByState.merge(pair.state(), 1, Integer::sum);
            storesByState.computeIfAbsent(pair.state(), state -> new HashSet<>()).add(pair.store());
        });

        var productChanges = new ArrayList<ProductChange>();
        byStateProduct.forEach((state, byProduct) -> byProduct.forEach((productId, accumulator) ->
                productChanges.add(new ProductChange(state, productId, accumulator.weightedLogSum,
                        accumulator.weightSum, accumulator.pairCount))));
        var coverage = pairsByState.entrySet().stream()
                .map(entry -> new StateCoverage(entry.getKey(), entry.getValue(), storesByState.get(entry.getKey()).size()))
                .toList();
        return new MonthChanges(productChanges, coverage);
    }

    private static Map<PairKey, PairMonth> pairMonths(Collection<PriceSample> samples) {
        var pricesByPair = new HashMap<PairKey, List<Double>>();
        var spendByPair = new HashMap<PairKey, Double>();
        for (var sample : samples) {
            var pair = new PairKey(sample.state(), sample.productId(), sample.store(), sample.basis());
            pricesByPair.computeIfAbsent(pair, key -> new ArrayList<>()).add(sample.price());
            spendByPair.merge(pair, sample.spend(), Double::sum);
        }
        var pairMonths = new HashMap<PairKey, PairMonth>();
        pricesByPair.forEach((pair, prices) -> pairMonths.put(pair, new PairMonth(median(prices), spendByPair.get(pair))));
        return pairMonths;
    }

    static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        var middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    /** The month's link of a group of rows: the spend-weighted geometric mean of their relatives. */
    public static OptionalDouble link(double weightedLogSum, double weightSum) {
        return weightSum > 0 ? OptionalDouble.of(Math.exp(weightedLogSum / weightSum)) : OptionalDouble.empty();
    }

    /** The official monthly % change as a link (0.4% → 1.004). */
    public static double linkOfPercent(BigDecimal changePercent) {
        return 1 + changePercent.doubleValue() / 100.0;
    }

    /**
     * The longest run of consecutive valid months inside the window; between runs of the same
     * length, the most recent. Empty when no month is valid.
     */
    public static Optional<Span> longestRecentRun(Span window, Set<YearMonth> validMonths) {
        Span best = null;
        YearMonth runStart = null;
        for (var month : window.months()) {
            if (!validMonths.contains(month)) {
                runStart = null;
                continue;
            }
            if (runStart == null) runStart = month;
            var run = new Span(runStart, month);
            if (best == null || run.length() >= best.length()) best = run;
        }
        return Optional.ofNullable(best);
    }

    /**
     * Chains the monthly links over the span: 100 at the month before the first one, then one
     * point per month. A month with no link keeps the previous level.
     */
    public static List<IndexPoint> chain(Span span, Map<YearMonth, Double> linkByMonth) {
        var points = new ArrayList<IndexPoint>();
        var index = BASE_INDEX;
        points.add(new IndexPoint(span.from().minusMonths(1), index));
        for (var month : span.months()) {
            index *= linkByMonth.getOrDefault(month, 1.0);
            points.add(new IndexPoint(month, index));
        }
        return points;
    }

    /** Change (%) from the base point to the last point of a chained series. */
    public static double changePercent(List<IndexPoint> series) {
        return series.get(series.size() - 1).index() / BASE_INDEX * 100.0 - 100.0;
    }

    /** Change (%) of chaining the given links (an item's months with data). */
    public static double changePercentOfLinks(Collection<Double> links) {
        return (links.stream().reduce(1.0, (product, link) -> product * link) - 1) * 100.0;
    }

    /**
     * The household basket's link for one month: the geometric mean of the community relatives of
     * the products the household buys, each weighing by the household's spend on it. Empty when
     * fewer than {@code minProducts} of them have a relative that month.
     */
    public static OptionalDouble basketLink(Map<UUID, Double> spendByProduct, Map<UUID, Double> relativeByProduct,
                                            int minProducts) {
        var weightedLogSum = 0.0;
        var weightSum = 0.0;
        var products = 0;
        for (var entry : spendByProduct.entrySet()) {
            var relative = relativeByProduct.get(entry.getKey());
            if (relative == null || relative <= 0 || entry.getValue() <= 0) continue;
            weightedLogSum += entry.getValue() * Math.log(relative);
            weightSum += entry.getValue();
            products++;
        }
        return products >= minProducts && products > 0 ? link(weightedLogSum, weightSum) : OptionalDouble.empty();
    }

    /** How many of the household's products have a community relative. */
    public static int pricedProducts(Map<UUID, Double> spendByProduct, Map<UUID, Double> relativeByProduct) {
        return (int) spendByProduct.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .filter(entry -> relativeByProduct.getOrDefault(entry.getKey(), 0.0) > 0)
                .count();
    }

    public static BigDecimal oneDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    public static BigDecimal twoDecimals(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
