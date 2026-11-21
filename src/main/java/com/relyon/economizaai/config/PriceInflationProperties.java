package com.relyon.economizaai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Preços e inflação": official food inflation (IBGE) next to how the prices paid in the markets
 * moved. The first block is edited in the admin at runtime; the rest is technical.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "economizaai.price-inflation")
public class PriceInflationProperties {

    /** Who the feature exists for. */
    public enum Audience { ADMINS, EVERYONE }

    private volatile Audience audience = Audience.ADMINS;
    /** Periods (months) the screen offers; the largest is the one it opens with. */
    private volatile List<Integer> periods = List.of(3, 6, 12);
    /** Root category slugs whose products count as food. */
    private volatile Set<String> foodCategories = Set.of();
    /** A month counts for Brasil or a state only with at least this many matched price pairs... */
    private volatile int minPairsPerMonth = 20;
    /** ...coming from at least this many stores. */
    private volatile int minStoresPerMonth = 3;
    /** A category or a food is listed only with at least this many pairs over the period. */
    private volatile int minPairsPerItem = 3;
    /** A price that moved more than this from one month to the next is taken as an error and dropped. */
    private volatile int maxMonthlyChangePercent = 100;
    /** "Sua cesta" needs at least this many of the household's products with a price change in the month. */
    private volatile int basketMinProducts = 5;
    private volatile boolean monthlyNotice = true;

    // ---- technical ----
    private int officialBackfillMonths = 36;
    private int officialRefreshMonths = 3;
    private int communityBackfillMonths = 24;
    private int communityRefreshMonths = 3;
    private Sidra sidra = new Sidra();

    /** The period the screen opens with, and the one used when the app asks for a period that is not offered. */
    public int initialPeriod() {
        return Collections.max(periods);
    }

    public int offeredPeriodOrInitial(Integer requestedMonths) {
        return requestedMonths != null && periods.contains(requestedMonths) ? requestedMonths : initialPeriod();
    }

    public void setFoodCategories(Set<String> foodCategories) {
        this.foodCategories = Collections.unmodifiableSet(new LinkedHashSet<>(foodCategories));
    }

    public void setPeriods(List<Integer> periods) {
        this.periods = List.copyOf(periods);
    }

    /** IBGE SIDRA, table 7060 (IPCA): where the official numbers come from. */
    @Getter
    @Setter
    public static class Sidra {
        private String baseUrl = "https://apisidra.ibge.gov.br";
        private String userAgent = "economizai";
        private int timeoutMs = 30000;
        private int maxAttempts = 3;
        /** "Alimentação no domicílio": the official number compared with the community's. */
        private String foodAtHomeItem = "7171";
        /** The food groups directly under it. */
        private List<String> groupItems = List.of();
    }
}
