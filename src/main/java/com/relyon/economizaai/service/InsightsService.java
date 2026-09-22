package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.MarketDiscountResponse;
import com.relyon.economizaai.dto.response.PriceHistoryResponse;
import com.relyon.economizaai.dto.response.SpendInsightsResponse;
import com.relyon.economizaai.dto.response.SpendInsightsResponse.CategoryBucket;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategoryView;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.config.CachingConfig;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsService {

    private static final LocalDateTime EPOCH_FLOOR = LocalDateTime.of(1900, Month.JANUARY, 1, 0, 0);
    private static final LocalDateTime EPOCH_CEIL = LocalDateTime.of(2999, Month.DECEMBER, 31, 23, 59, 59);
    private static final String ENUM_PREFIX = "enum:";

    private final InsightsRepository insightsRepository;
    private final ProductRepository productRepository;
    private final MarketNameService marketNameService;
    private final HouseholdProductCategoryOverrideService categoryOverrideService;
    private final SubscriptionGateService subscriptionGate;
    private final HouseholdProductAliasService householdProductAliasService;

    @Transactional(readOnly = true)
    @Cacheable(value = CachingConfig.INSIGHTS_SPEND_CACHE,
            key = "#user.household.id + ':' + @householdCacheGen.get(#user.household.id) + ':' + #user.subscriptionTier + ':' + #from + ':' + #to")
    public SpendInsightsResponse spend(User user, LocalDateTime from, LocalDateTime to) {
        var householdId = user.getHousehold().getId();
        // FREE tier: clamp the lower bound to the allowed history window. Tier is
        // part of the cache key so FREE/PRO results don't collide.
        from = subscriptionGate.clampFrom(user, from);
        var fromBound = from != null ? from : EPOCH_FLOOR;
        var toBound = to != null ? to : EPOCH_CEIL;
        var total = insightsRepository.totalSpend(householdId, fromBound, toBound);
        var totalDiscount = toBigDecimal(insightsRepository.totalDiscount(householdId, fromBound, toBound));
        var byMonth = buildMonthBuckets(householdId, fromBound, toBound);
        var byWeek = buildWeekBuckets(householdId, fromBound, toBound);
        var byMarket = buildMarketBuckets(householdId, fromBound, toBound);
        var byCategory = buildCategoryBuckets(householdId, fromBound, toBound);
        log.debug("Spend insights for household {} (from={}, to={}): total={} discount={}",
                householdId, from, to, total, totalDiscount);
        return new SpendInsightsResponse(from, to, total, totalDiscount, byMonth, byWeek, byMarket, byCategory);
    }

    private List<SpendInsightsResponse.MonthBucket> buildMonthBuckets(UUID householdId,
                                                                      LocalDateTime fromBound, LocalDateTime toBound) {
        var discountByMonth = discountMap(insightsRepository.discountByMonth(householdId, fromBound, toBound),
                row -> pairKey(((Number) row[0]).intValue(), ((Number) row[1]).intValue()));
        return insightsRepository.spendByMonth(householdId, fromBound, toBound).stream()
                .map(row -> new SpendInsightsResponse.MonthBucket(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue(),
                        toBigDecimal(row[2]),
                        discountFor(discountByMonth, pairKey(((Number) row[0]).intValue(), ((Number) row[1]).intValue())),
                        ((Number) row[3]).longValue()))
                .toList();
    }

    private List<SpendInsightsResponse.WeekBucket> buildWeekBuckets(UUID householdId,
                                                                    LocalDateTime fromBound, LocalDateTime toBound) {
        var discountByWeek = discountMap(insightsRepository.discountByWeek(householdId, fromBound, toBound),
                row -> pairKey(((Number) row[0]).intValue(), ((Number) row[1]).intValue()));
        return insightsRepository.spendByWeek(householdId, fromBound, toBound).stream()
                .map(row -> new SpendInsightsResponse.WeekBucket(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue(),
                        toBigDecimal(row[2]),
                        discountFor(discountByWeek, pairKey(((Number) row[0]).intValue(), ((Number) row[1]).intValue())),
                        ((Number) row[3]).longValue()))
                .toList();
    }

    private List<SpendInsightsResponse.MarketBucket> buildMarketBuckets(UUID householdId,
                                                                        LocalDateTime fromBound, LocalDateTime toBound) {
        var discountByMarket = discountMap(insightsRepository.discountByMarket(householdId, fromBound, toBound),
                row -> (String) row[0]);
        var marketBuckets = insightsRepository.spendByMarket(householdId, fromBound, toBound).stream()
                .map(row -> new SpendInsightsResponse.MarketBucket(
                        (String) row[0],
                        (String) row[1],
                        (String) row[1],
                        toBigDecimal(row[2]),
                        discountFor(discountByMarket, (String) row[0]),
                        ((Number) row[3]).longValue()))
                .toList();
        var marketOverrides = marketNameService.resolveNames(householdId, marketBuckets.stream()
                .map(bucket -> bucket.cnpj())
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return marketBuckets.stream()
                .map(bucket -> bucket.withMarketFriendlyName(marketNameService.applyOverride(
                        marketOverrides, bucket.cnpj(), bucket.marketName())))
                .toList();
    }

    private List<CategoryBucket> buildCategoryBuckets(UUID householdId,
                                                      LocalDateTime fromBound, LocalDateTime toBound) {
        return insightsRepository.spendByCategory(householdId, fromBound, toBound).stream()
                .map(row -> SpendInsightsResponse.CategoryBucket.ofEnum(
                        (ProductCategory) row[0],
                        toBigDecimal(row[1]),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    /** Index receipt-level discount rows by a bucket key for O(1) lookup when merging into spend buckets. */
    private static Map<String, BigDecimal> discountMap(List<Object[]> rows, Function<Object[], String> keyOf) {
        var map = new HashMap<String, BigDecimal>();
        for (var row : rows) {
            var key = keyOf.apply(row);
            if (key != null) map.merge(key, toBigDecimal(row[row.length - 1]), BigDecimal::add);
        }
        return map;
    }

    private static BigDecimal discountFor(Map<String, BigDecimal> discounts, String key) {
        return discounts.getOrDefault(key, BigDecimal.ZERO);
    }

    /** Stable key joining two grouping ints (year+month or year+week). */
    private static String pairKey(int first, int second) {
        return first + "-" + second;
    }

    @Transactional(readOnly = true)
    public List<SpendInsightsResponse.MarketBucket> topMarkets(User user, LocalDateTime from, LocalDateTime to, int limit) {
        return spend(user, from, to).byMarket().stream().limit(Math.max(0, limit)).toList();
    }

    /**
     * Markets ranked by the discount the household received there, biggest first.
     * Markets that gave no discount are excluded. Reuses the cached {@link #spend}
     * aggregation (gross spend + per-market discount), so it costs nothing extra.
     */
    @Transactional(readOnly = true)
    public List<MarketDiscountResponse> topMarketsByDiscount(User user, LocalDateTime from, LocalDateTime to, int limit) {
        return spend(user, from, to).byMarket().stream()
                .filter(bucket -> bucket.discount() != null && bucket.discount().signum() > 0)
                .sorted(Comparator.comparing(SpendInsightsResponse.MarketBucket::discount).reversed())
                .limit(Math.max(0, limit))
                .map(MarketDiscountResponse::from)
                .toList();
    }

    /** Backwards-compatible entry point — uses the default HOUSEHOLD lens. */
    @Transactional(readOnly = true)
    public List<CategoryBucket> topCategories(User user, LocalDateTime from, LocalDateTime to, int limit) {
        return topCategories(user, from, to, limit, CategoryView.HOUSEHOLD);
    }

    @Transactional(readOnly = true)
    public List<CategoryBucket> topCategories(User user, LocalDateTime from, LocalDateTime to,
                                              int limit, CategoryView categoryView) {
        var safeLimit = Math.max(0, limit);
        if (categoryView == CategoryView.GLOBAL) {
            return spend(user, from, to).byCategory().stream().limit(safeLimit).toList();
        }
        return householdCategoryBuckets(user, from, to).stream().limit(safeLimit).toList();
    }

    /**
     * HOUSEHOLD-lens category buckets: each product counts once, under its
     * EFFECTIVE category. We pull product-granularity spend and re-bucket in code
     * by the override label (custom name / corrected enum) or the global enum —
     * correct and free of the double-counting a SQL group-by-p.category would
     * cause once overrides exist.
     */
    private List<CategoryBucket> householdCategoryBuckets(User user, LocalDateTime from, LocalDateTime to) {
        var householdId = user.getHousehold().getId();
        from = subscriptionGate.clampFrom(user, from);
        var fromBound = from != null ? from : EPOCH_FLOOR;
        var toBound = to != null ? to : EPOCH_CEIL;
        var rows = insightsRepository.spendByProduct(householdId, fromBound, toBound);
        var productIds = rows.stream()
                .map(row -> (UUID) row[0])
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var overrides = categoryOverrideService.overrideKeysByProduct(householdId, productIds);

        var accumulators = new LinkedHashMap<String, CategoryAccumulator>();
        for (var row : rows) {
            var productId = (UUID) row[0];
            var globalCategory = (ProductCategory) row[1];
            var total = toBigDecimal(row[2]);
            var itemCount = ((Number) row[3]).longValue();
            var override = productId != null ? overrides.get(productId) : null;
            var categoryName = globalCategory != null ? globalCategory.name() : ProductCategory.OTHER.name();
            var label = override != null ? override.label() : categoryName;
            // Discriminated bucket key so a custom category named like an enum doesn't merge.
            var bucketKey = override != null ? override.key() : ENUM_PREFIX + categoryName;
            // Derive the enum category from the (collision-free) key, not the label: a
            // custom bucket carries a null enum even when its name matches one.
            var effectiveCategory = categoryFromKey(bucketKey);
            accumulators.computeIfAbsent(bucketKey, key -> new CategoryAccumulator(effectiveCategory, label))
                    .add(total, itemCount);
        }
        return accumulators.values().stream()
                .map(CategoryAccumulator::toBucket)
                .sorted(Comparator.comparing(CategoryBucket::total).reversed())
                .toList();
    }

    /**
     * The enum category a discriminated bucket key maps to. A {@code custom:...} key
     * has no enum (returns null); an {@code enum:NAME} key resolves to that enum.
     */
    private static ProductCategory categoryFromKey(String bucketKey) {
        if (!bucketKey.startsWith(ENUM_PREFIX)) return null;
        var name = bucketKey.substring(ENUM_PREFIX.length());
        for (var category : ProductCategory.values()) {
            if (category.name().equals(name)) return category;
        }
        return null;
    }

    private static final class CategoryAccumulator {
        private final ProductCategory category;
        private final String label;
        private BigDecimal total = BigDecimal.ZERO;
        private long itemCount = 0;

        CategoryAccumulator(ProductCategory category, String label) {
            this.category = category;
            this.label = label;
        }

        void add(BigDecimal amount, long items) {
            total = total.add(amount);
            itemCount += items;
        }

        CategoryBucket toBucket() {
            return new CategoryBucket(category, label, total, itemCount);
        }
    }

    @Transactional(readOnly = true)
    public PriceHistoryResponse priceHistory(User user, UUID productId, LocalDateTime from, LocalDateTime to) {
        var product = productRepository.findById(productId).orElseThrow(ProductNotFoundException::new);
        from = subscriptionGate.clampFrom(user, from);
        var fromBound = from != null ? from : EPOCH_FLOOR;
        var toBound = to != null ? to : EPOCH_CEIL;
        var householdId = user.getHousehold().getId();
        var rawPoints = insightsRepository
                .priceHistoryForProduct(householdId, productId, fromBound, toBound).stream()
                .map(row -> new PriceHistoryResponse.PricePoint(
                        (LocalDateTime) row[0],
                        (String) row[1],
                        (String) row[2],
                        (String) row[2],
                        toBigDecimal(row[3]),
                        toBigDecimal(row[4])))
                .toList();
        var overrides = marketNameService.resolveNames(householdId, rawPoints.stream()
                .map(point -> point.marketCnpj())
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        var points = rawPoints.stream()
                .map(point -> point.withMarketFriendlyName(marketNameService.applyOverride(
                        overrides, point.marketCnpj(), point.marketName())))
                .toList();
        var friendlyDescription = householdProductAliasService.findFor(user.getHousehold(), product);
        return new PriceHistoryResponse(product.getId(), product.getNormalizedName(), friendlyDescription, points);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(value.toString());
    }
}
