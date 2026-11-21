package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.service.inflation.CommunityIndexMath.PriceSample;
import com.relyon.economizaai.service.inflation.CommunityIndexMath.Span;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CommunityIndexMathTest {

    private static final UUID RICE = UUID.randomUUID();
    private static final UUID BEANS = UUID.randomUUID();
    private static final String STORE_A = "11111111000101";
    private static final String STORE_B = "22222222000102";
    private static final String KILO = "KG";
    private static final double MAX_CHANGE = 100;

    private static PriceSample sample(UUID product, String store, String state, String basis, double price, double spend) {
        return new PriceSample(product, store, state, basis, price, spend);
    }

    private static YearMonth month(Month month) {
        return YearMonth.of(2026, month);
    }

    @Test
    void aPairLinksTheMonthOnlyWhenTheSameProductAtTheSameStoreHasAPriceInBothMonths() {
        var previous = List.of(
                sample(RICE, STORE_A, "RS", KILO, 10, 10),
                sample(BEANS, STORE_A, "RS", KILO, 8, 8));
        var current = List.of(
                sample(RICE, STORE_A, "RS", KILO, 11, 11),
                sample(RICE, STORE_B, "RS", KILO, 9, 9),
                sample(BEANS, STORE_B, "RS", KILO, 8, 8));

        var changes = CommunityIndexMath.monthChanges(previous, current, MAX_CHANGE);

        assertThat(changes.productChanges()).singleElement().satisfies(change -> {
            assertThat(change.productId()).isEqualTo(RICE);
            assertThat(change.pairCount()).isEqualTo(1);
            assertThat(CommunityIndexMath.link(change.weightedLogSum(), change.weightSum()).orElseThrow())
                    .isCloseTo(1.10, within(1e-9));
        });
        assertThat(changes.coverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.state()).isEqualTo("RS");
            assertThat(coverage.pairCount()).isEqualTo(1);
            assertThat(coverage.storeCount()).isEqualTo(1);
        });
    }

    @Test
    void theMonthlyPriceOfAPairIsTheMedianOfItsPrices() {
        var previous = List.of(
                sample(RICE, STORE_A, "RS", KILO, 9, 9),
                sample(RICE, STORE_A, "RS", KILO, 10, 10),
                sample(RICE, STORE_A, "RS", KILO, 30, 30));
        var current = List.of(sample(RICE, STORE_A, "RS", KILO, 12, 12));

        var change = CommunityIndexMath.monthChanges(previous, current, MAX_CHANGE).productChanges().get(0);

        assertThat(CommunityIndexMath.link(change.weightedLogSum(), change.weightSum()).orElseThrow())
                .isCloseTo(1.20, within(1e-9));
    }

    @Test
    void pricesOnDifferentUnitBasesAreNeverCompared() {
        var previous = List.of(sample(RICE, STORE_A, "RS", KILO, 5, 25));
        var current = List.of(sample(RICE, STORE_A, "RS", CommunityIndexMath.RAW_BASIS, 25, 25));

        var changes = CommunityIndexMath.monthChanges(previous, current, MAX_CHANGE);

        assertThat(changes.productChanges()).isEmpty();
        assertThat(changes.coverage()).isEmpty();
    }

    @Test
    void theSameProductOnTwoBasesAtOneStoreMakesTwoPairs() {
        var previous = List.of(
                sample(RICE, STORE_A, "RS", KILO, 5, 25),
                sample(RICE, STORE_A, "RS", CommunityIndexMath.RAW_BASIS, 25, 25));
        var current = List.of(
                sample(RICE, STORE_A, "RS", KILO, 5.5, 27.5),
                sample(RICE, STORE_A, "RS", CommunityIndexMath.RAW_BASIS, 27.5, 27.5));

        var changes = CommunityIndexMath.monthChanges(previous, current, MAX_CHANGE);

        assertThat(changes.productChanges()).singleElement()
                .satisfies(change -> assertThat(change.pairCount()).isEqualTo(2));
        assertThat(changes.coverage().get(0).storeCount()).isEqualTo(1);
    }

    @Test
    void aChangeBeyondTheMaximumIsDroppedInBothDirections_andTheMaximumDecidesIt() {
        var previous = List.of(
                sample(RICE, STORE_A, "RS", KILO, 10, 10),
                sample(BEANS, STORE_A, "RS", KILO, 10, 10));
        var current = List.of(
                sample(RICE, STORE_A, "RS", KILO, 25, 25),
                sample(BEANS, STORE_A, "RS", KILO, 4, 4));

        assertThat(CommunityIndexMath.monthChanges(previous, current, 100).productChanges()).isEmpty();
        assertThat(CommunityIndexMath.monthChanges(previous, current, 150).productChanges()).hasSize(2);
        assertThat(CommunityIndexMath.monthChanges(previous, current, 149).productChanges()).isEmpty();
    }

    @Test
    void aChangeExactlyAtTheMaximumIsKept() {
        var previous = List.of(sample(RICE, STORE_A, "RS", KILO, 10, 10));
        var current = List.of(sample(RICE, STORE_A, "RS", KILO, 20, 20));

        assertThat(CommunityIndexMath.monthChanges(previous, current, 100).productChanges()).hasSize(1);
    }

    @Test
    void eachPairWeighsByTheAverageOfItsSpendInTheTwoMonths() {
        var previous = List.of(
                sample(RICE, STORE_A, "RS", KILO, 10, 100),
                sample(RICE, STORE_B, "RS", KILO, 10, 10));
        var current = List.of(
                sample(RICE, STORE_A, "RS", KILO, 12, 200),
                sample(RICE, STORE_B, "RS", KILO, 9, 30));

        var change = CommunityIndexMath.monthChanges(previous, current, MAX_CHANGE).productChanges().get(0);

        var expectedWeightSum = 150.0 + 20.0;
        var expectedLogSum = 150.0 * Math.log(1.2) + 20.0 * Math.log(0.9);
        assertThat(change.weightSum()).isCloseTo(expectedWeightSum, within(1e-9));
        assertThat(change.weightedLogSum()).isCloseTo(expectedLogSum, within(1e-9));
        assertThat(CommunityIndexMath.link(change.weightedLogSum(), change.weightSum()).orElseThrow())
                .isCloseTo(Math.exp(expectedLogSum / expectedWeightSum), within(1e-9));
    }

    @Test
    void statesAreKeptApart_andStoresAreCountedOncePerState() {
        var previous = List.of(
                sample(RICE, STORE_A, "RS", KILO, 10, 10),
                sample(BEANS, STORE_A, "RS", KILO, 10, 10),
                sample(RICE, STORE_B, "SP", KILO, 10, 10));
        var current = List.of(
                sample(RICE, STORE_A, "RS", KILO, 11, 11),
                sample(BEANS, STORE_A, "RS", KILO, 11, 11),
                sample(RICE, STORE_B, "SP", KILO, 12, 12));

        var changes = CommunityIndexMath.monthChanges(previous, current, MAX_CHANGE);

        assertThat(changes.productChanges()).hasSize(3);
        assertThat(changes.coverage()).extracting(coverage -> coverage.state() + ":" + coverage.pairCount() + ":" + coverage.storeCount())
                .containsExactly("RS:2:1", "SP:1:1");
    }

    @Test
    void aGroupWithNoWeightHasNoLink() {
        assertThat(CommunityIndexMath.link(0, 0)).isEmpty();
    }

    @Test
    void theSeriesChainsTheLinksFromABaseOf100AtTheMonthBeforeTheFirst() {
        var span = new Span(month(Month.JUNE), month(Month.AUGUST));
        var links = Map.of(month(Month.JUNE), 1.10, month(Month.JULY), 1.00, month(Month.AUGUST), 0.95);

        var series = CommunityIndexMath.chain(span, links);

        assertThat(series).extracting(CommunityIndexMath.IndexPoint::month)
                .containsExactly(month(Month.MAY), month(Month.JUNE), month(Month.JULY), month(Month.AUGUST));
        assertThat(series.get(0).index()).isEqualTo(100.0);
        assertThat(series.get(1).index()).isCloseTo(110.0, within(1e-9));
        assertThat(series.get(3).index()).isCloseTo(104.5, within(1e-9));
        assertThat(CommunityIndexMath.changePercent(series)).isCloseTo(4.5, within(1e-9));
    }

    @Test
    void anOfficialPercentageBecomesALink() {
        assertThat(CommunityIndexMath.linkOfPercent(new BigDecimal("0.40"))).isCloseTo(1.004, within(1e-12));
        assertThat(CommunityIndexMath.linkOfPercent(new BigDecimal("-1.25"))).isCloseTo(0.9875, within(1e-12));
    }

    @Test
    void theSpanIsTheLongestRunOfValidMonths() {
        var window = new Span(month(Month.JANUARY), month(Month.AUGUST));
        var valid = Set.of(month(Month.FEBRUARY), month(Month.MARCH), month(Month.APRIL), month(Month.JULY), month(Month.AUGUST));

        assertThat(CommunityIndexMath.longestRecentRun(window, valid))
                .contains(new Span(month(Month.FEBRUARY), month(Month.APRIL)));
    }

    @Test
    void betweenRunsOfTheSameLengthTheMostRecentWins() {
        var window = new Span(month(Month.JANUARY), month(Month.AUGUST));
        var valid = Set.of(month(Month.FEBRUARY), month(Month.MARCH), month(Month.JUNE), month(Month.JULY));

        assertThat(CommunityIndexMath.longestRecentRun(window, valid))
                .contains(new Span(month(Month.JUNE), month(Month.JULY)));
    }

    @Test
    void monthsOutsideTheWindowDoNotCount_andNoValidMonthMeansNoSpan() {
        var window = new Span(month(Month.JUNE), month(Month.AUGUST));

        assertThat(CommunityIndexMath.longestRecentRun(window, Set.of(month(Month.MAY), month(Month.JUNE))))
                .contains(new Span(month(Month.JUNE), month(Month.JUNE)));
        assertThat(CommunityIndexMath.longestRecentRun(window, Set.of(month(Month.JANUARY)))).isEmpty();
    }

    @Test
    void aShortenedSpanGivesBothSeriesTheSameMonths() {
        var window = new Span(month(Month.JANUARY), month(Month.JUNE));
        var span = CommunityIndexMath.longestRecentRun(window, Set.of(month(Month.MAY), month(Month.JUNE))).orElseThrow();
        var officialLinks = Map.of(
                month(Month.JANUARY), 1.02, month(Month.FEBRUARY), 1.02, month(Month.MARCH), 1.02,
                month(Month.APRIL), 1.02, month(Month.MAY), 1.01, month(Month.JUNE), 1.01);
        var communityLinks = Map.of(month(Month.MAY), 0.99, month(Month.JUNE), 1.03);

        var official = CommunityIndexMath.chain(span, officialLinks);
        var community = CommunityIndexMath.chain(span, communityLinks);

        assertThat(official).extracting(CommunityIndexMath.IndexPoint::month)
                .isEqualTo(community.stream().map(CommunityIndexMath.IndexPoint::month).toList())
                .containsExactly(month(Month.APRIL), month(Month.MAY), month(Month.JUNE));
        assertThat(CommunityIndexMath.changePercent(official)).isCloseTo(2.01, within(1e-9));
        assertThat(span.length()).isLessThan(window.length());
    }

    @Test
    void anItemChangesByTheProductOfItsMonthlyLinks() {
        assertThat(CommunityIndexMath.changePercentOfLinks(List.of(1.10, 0.95))).isCloseTo(4.5, within(1e-9));
        assertThat(CommunityIndexMath.changePercentOfLinks(List.of())).isZero();
    }

    @Test
    void theBasketWeighsEachProductByWhatTheHouseholdSpentOnIt() {
        var spend = Map.of(RICE, 300.0, BEANS, 100.0);
        var relatives = Map.of(RICE, 1.20, BEANS, 0.90);

        var link = CommunityIndexMath.basketLink(spend, relatives, 2).orElseThrow();

        assertThat(link).isCloseTo(Math.exp((300 * Math.log(1.20) + 100 * Math.log(0.90)) / 400), within(1e-9));
        assertThat(link).isGreaterThan(Math.sqrt(1.20 * 0.90));
    }

    @Test
    void theBasketNeedsTheMinimumOfProductsWithAKnownChange() {
        var spend = Map.of(RICE, 300.0, BEANS, 100.0, UUID.randomUUID(), 50.0);
        var relatives = Map.of(RICE, 1.20, BEANS, 0.90);

        assertThat(CommunityIndexMath.pricedProducts(spend, relatives)).isEqualTo(2);
        assertThat(CommunityIndexMath.basketLink(spend, relatives, 3)).isEmpty();
        assertThat(CommunityIndexMath.basketLink(spend, relatives, 2)).isPresent();
    }

    @Test
    void percentagesHaveOneDecimal() {
        assertThat(CommunityIndexMath.oneDecimal(4.449)).isEqualByComparingTo("4.4");
        assertThat(CommunityIndexMath.oneDecimal(-0.36)).isEqualByComparingTo("-0.4");
        assertThat(CommunityIndexMath.oneDecimal(-0.04).signum()).isZero();
    }
}
