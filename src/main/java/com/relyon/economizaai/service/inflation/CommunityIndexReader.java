package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.repository.CommunityPriceChangeRepository;
import com.relyon.economizaai.repository.CommunityPriceCoverageRepository;
import com.relyon.economizaai.service.inflation.CommunityIndexMath.Span;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the stored community price changes of a window of months and answers, for Brasil or for
 * one state, which months have enough data and what the monthly links are.
 */
@Service
@RequiredArgsConstructor
public class CommunityIndexReader {

    private final CommunityPriceChangeRepository changeRepository;
    private final CommunityPriceCoverageRepository coverageRepository;
    private final PriceInflationProperties properties;

    public record Coverage(int pairCount, int storeCount) {
        static final Coverage NONE = new Coverage(0, 0);

        Coverage plus(Coverage other) {
            return new Coverage(pairCount + other.pairCount, storeCount + other.storeCount);
        }
    }

    private record Totals(double weightedLogSum, double weightSum) {
        Totals plus(Totals other) {
            return new Totals(weightedLogSum + other.weightedLogSum, weightSum + other.weightSum);
        }
    }

    /** The community numbers of one window. A null state means Brasil (every state together). */
    public static final class WindowData {

        private final Span window;
        private final int minPairsPerMonth;
        private final int minStoresPerMonth;
        private final Map<YearMonth, Map<String, Coverage>> coverageByMonth = new HashMap<>();
        private final Map<YearMonth, Map<String, Totals>> totalsByMonth = new HashMap<>();

        private WindowData(Span window, int minPairsPerMonth, int minStoresPerMonth) {
            this.window = window;
            this.minPairsPerMonth = minPairsPerMonth;
            this.minStoresPerMonth = minStoresPerMonth;
        }

        public Span window() {
            return window;
        }

        /** A store belongs to one state, so Brasil's stores are the states' stores added up. */
        public Coverage coverage(YearMonth month, String state) {
            var byState = coverageByMonth.getOrDefault(month, Map.of());
            return state == null
                    ? byState.values().stream().reduce(Coverage.NONE, Coverage::plus)
                    : byState.getOrDefault(state, Coverage.NONE);
        }

        public boolean isValid(YearMonth month, String state) {
            var coverage = coverage(month, state);
            return coverage.pairCount() > 0 && coverage.pairCount() >= minPairsPerMonth
                    && coverage.storeCount() >= minStoresPerMonth;
        }

        /** The months compared: the longest run of valid months, the most recent one between equals. */
        public Optional<Span> comparableSpan(String state) {
            var validMonths = new HashSet<YearMonth>();
            window.months().stream().filter(month -> isValid(month, state)).forEach(validMonths::add);
            return CommunityIndexMath.longestRecentRun(window, validMonths);
        }

        public Map<YearMonth, Double> links(String state) {
            var links = new HashMap<YearMonth, Double>();
            totalsByMonth.forEach((month, byState) -> {
                var totals = state == null
                        ? byState.values().stream().reduce(new Totals(0, 0), Totals::plus)
                        : byState.getOrDefault(state, new Totals(0, 0));
                CommunityIndexMath.link(totals.weightedLogSum(), totals.weightSum())
                        .ifPresent(link -> links.put(month, link));
            });
            return links;
        }

        public int pairCount(Span span, String state) {
            return span.months().stream().mapToInt(month -> coverage(month, state).pairCount()).sum();
        }

        /** The most stores any month of the span had. */
        public int storeCount(Span span, String state) {
            return span.months().stream().mapToInt(month -> coverage(month, state).storeCount()).max().orElse(0);
        }

        public Set<String> states() {
            var states = new HashSet<String>();
            coverageByMonth.values().forEach(byState -> states.addAll(byState.keySet()));
            return states;
        }
    }

    public WindowData read(Span window) {
        var data = new WindowData(window, properties.getMinPairsPerMonth(), properties.getMinStoresPerMonth());
        var fromMonth = window.from().atDay(1);
        var toMonth = window.to().atDay(1);
        coverageRepository.findAllByReferenceMonthBetween(fromMonth, toMonth).forEach(coverage ->
                data.coverageByMonth.computeIfAbsent(YearMonth.from(coverage.getReferenceMonth()), month -> new HashMap<>())
                        .put(coverage.getState(), new Coverage(coverage.getPairCount(), coverage.getStoreCount())));
        changeRepository.monthlyTotalsByState(fromMonth, toMonth).forEach(row ->
                data.totalsByMonth.computeIfAbsent(YearMonth.from((LocalDate) row[0]), month -> new HashMap<>())
                        .put((String) row[1], new Totals(((Number) row[2]).doubleValue(), ((Number) row[3]).doubleValue())));
        return data;
    }
}
