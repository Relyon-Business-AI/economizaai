package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.MarketDiscountResponse;
import com.relyon.economizaai.dto.response.PriceHistoryResponse;
import com.relyon.economizaai.dto.response.SpendInsightsResponse;
import com.relyon.economizaai.dto.response.SpendInsightsResponse.CategoryBucket;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.config.CachingConfig;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
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

    private final InsightsRepository insightsRepository;
    private final ProductRepository productRepository;
    private final MarketNameService marketNameService;
    private final HouseholdProductCategoryOverrideService categoryOverrideService;
    private final SubscriptionGateService subscriptionGate;
    private final HouseholdProductAliasService householdProductAliasService;
    private final CategoryResolver categoryResolver;

    /** Backwards-compatible entry point — every segment (ALL scope). */
    @Transactional(readOnly = true)
    public SpendInsightsResponse spend(User user, LocalDateTime from, LocalDateTime to) {
        return spend(user, from, to, MarketScope.ALL);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CachingConfig.INSIGHTS_SPEND_CACHE,
            key = "#user.household.id + ':' + @householdCacheGen.get(#user.household.id) + ':' + #user.subscriptionTier + ':' + #scope + ':' + #from + ':' + #to + ':' + T(org.springframework.context.i18n.LocaleContextHolder).getLocale().getLanguage()")
    public SpendInsightsResponse spend(User user, LocalDateTime from, LocalDateTime to, MarketScope scope) {
        var householdId = user.getHousehold().getId();
        // FREE tier: clamp the lower bound to the allowed history window. Tier is
        // part of the cache key so FREE/PRO results don't collide.
        from = subscriptionGate.clampFrom(user, from);
        var fromBound = from != null ? from : EPOCH_FLOOR;
        var toBound = to != null ? to : EPOCH_CEIL;
        var scopeName = scope.name();
        var cnpjs = scopeCnpjs(householdId, scope);
        var total = insightsRepository.totalSpend(householdId, fromBound, toBound, scopeName, cnpjs);
        var totalDiscount = toBigDecimal(insightsRepository.totalDiscount(householdId, fromBound, toBound, scopeName, cnpjs));
        var byMonth = buildMonthBuckets(householdId, fromBound, toBound, scopeName, cnpjs);
        var byWeek = buildWeekBuckets(householdId, fromBound, toBound, scopeName, cnpjs);
        var byMarket = buildMarketBuckets(householdId, fromBound, toBound, scopeName, cnpjs);
        var byCategory = buildCategoryBuckets(householdId, fromBound, toBound, scopeName, cnpjs);
        log.debug("Spend insights for household {} (from={}, to={}, scope={}): total={} discount={}",
                householdId, from, to, scope, total, totalDiscount);
        return new SpendInsightsResponse(from, to, total, totalDiscount, byMonth, byWeek, byMarket, byCategory);
    }

    /**
     * The CNPJ set the scope predicate filters on. For SUPPORTED/OTHER it's the
     * household's grocery/pharmacy CNPJs; for ALL (and when the set is empty) a
     * sentinel keeps the {@code IN}/{@code NOT IN} well-formed (never an empty list).
     */
    private List<String> scopeCnpjs(UUID householdId, MarketScope scope) {
        if (scope == MarketScope.ALL) return List.of("__none__");
        var supported = insightsRepository.supportedCnpjs(householdId, MerchantSupportGate.supportedSegments());
        return supported.isEmpty() ? List.of("__none__") : supported;
    }

    private List<SpendInsightsResponse.MonthBucket> buildMonthBuckets(UUID householdId,
                                                                      LocalDateTime fromBound, LocalDateTime toBound,
                                                                      String scope, List<String> cnpjs) {
        var discountByMonth = discountMap(insightsRepository.discountByMonth(householdId, fromBound, toBound, scope, cnpjs),
                row -> pairKey(((Number) row[0]).intValue(), ((Number) row[1]).intValue()));
        return insightsRepository.spendByMonth(householdId, fromBound, toBound, scope, cnpjs).stream()
                .map(row -> new SpendInsightsResponse.MonthBucket(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue(),
                        toBigDecimal(row[2]),
                        discountFor(discountByMonth, pairKey(((Number) row[0]).intValue(), ((Number) row[1]).intValue())),
                        ((Number) row[3]).longValue()))
                .toList();
    }

    private List<SpendInsightsResponse.WeekBucket> buildWeekBuckets(UUID householdId,
                                                                    LocalDateTime fromBound, LocalDateTime toBound,
                                                                    String scope, List<String> cnpjs) {
        var discountByWeek = discountMap(insightsRepository.discountByWeek(householdId, fromBound, toBound, scope, cnpjs),
                row -> pairKey(((Number) row[0]).intValue(), ((Number) row[1]).intValue()));
        return insightsRepository.spendByWeek(householdId, fromBound, toBound, scope, cnpjs).stream()
                .map(row -> new SpendInsightsResponse.WeekBucket(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue(),
                        toBigDecimal(row[2]),
                        discountFor(discountByWeek, pairKey(((Number) row[0]).intValue(), ((Number) row[1]).intValue())),
                        ((Number) row[3]).longValue()))
                .toList();
    }

    private List<SpendInsightsResponse.MarketBucket> buildMarketBuckets(UUID householdId,
                                                                        LocalDateTime fromBound, LocalDateTime toBound,
                                                                        String scope, List<String> cnpjs) {
        var discountByMarket = discountMap(insightsRepository.discountByMarket(householdId, fromBound, toBound, scope, cnpjs),
                row -> (String) row[0]);
        var marketBuckets = insightsRepository.spendByMarket(householdId, fromBound, toBound, scope, cnpjs).stream()
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
                                                      LocalDateTime fromBound, LocalDateTime toBound,
                                                      String scope, List<String> cnpjs) {
        var locale = LocaleContextHolder.getLocale();
        var rows = insightsRepository.spendByProductNode(householdId, fromBound, toBound, scope, cnpjs);
        var productIds = rows.stream().map(row -> (UUID) row[0]).filter(Objects::nonNull).distinct().toList();
        var effective = categoryOverrideService.effectiveCategoryByProduct(householdId, productIds);

        var byKey = new LinkedHashMap<String, BucketAccumulator>();
        for (var row : rows) {
            var productId = (UUID) row[0];
            var globalNodeId = (UUID) row[1];
            var override = productId != null ? effective.get(productId) : null;
            String key, slug, label, parentSlug, icon, color;
            if (override != null) {
                key = override.key(); slug = override.slug(); label = override.label();
                parentSlug = override.parentSlug(); icon = override.icon(); color = override.color();
            } else {
                slug = globalNodeId != null ? categoryResolver.slugForId(globalNodeId) : null;
                label = globalNodeId != null ? categoryResolver.labelFor(globalNodeId, locale) : null;
                key = slug; parentSlug = null; icon = null; color = null;
            }
            if (key == null) { key = "outros"; slug = "outros"; label = "en".equals(locale.getLanguage()) ? "Other" : "Outros"; }
            if (label == null) label = slug;
            var finalSlug = slug; var finalLabel = label; var finalParent = parentSlug;
            var finalIcon = icon; var finalColor = color;
            byKey.computeIfAbsent(key, ignored -> new BucketAccumulator(finalSlug, finalLabel, finalParent, finalIcon, finalColor))
                    .add(toBigDecimal(row[2]), ((Number) row[3]).longValue());
        }
        return byKey.values().stream()
                .sorted(Comparator.comparing(BucketAccumulator::total).reversed())
                .map(BucketAccumulator::toBucket)
                .toList();
    }

    /** Accumulates spend per EFFECTIVE category (taxonomy node or household custom). */
    private final class BucketAccumulator {
        private final String slug;
        private final String label;
        private final String parentSlug;
        private final String icon;
        private final String color;
        private BigDecimal total = BigDecimal.ZERO;
        private long itemCount = 0;
        BucketAccumulator(String slug, String label, String parentSlug, String icon, String color) {
            this.slug = slug; this.label = label; this.parentSlug = parentSlug; this.icon = icon; this.color = color;
        }
        void add(BigDecimal amount, long count) { total = total.add(amount); itemCount += count; }
        BigDecimal total() { return total; }
        CategoryBucket toBucket() {
            if (icon != null || color != null || parentSlug != null) {
                return CategoryBucket.ofCustom(slug, label, parentSlug, icon, color, total, itemCount);
            }
            // Enum derivado do nó — compat com bundles ANTIGOS do app (renderizam por enum e
            // mostravam tudo "Outros" com category nulo). Categoria sem enum legado fica nula.
            var legacy = categoryResolver.legacyEnumForNode(categoryResolver.idForSlug(slug));
            return CategoryBucket.ofNode(slug, label, legacy, total, itemCount);
        }
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
        return topMarkets(user, from, to, limit, MarketScope.ALL);
    }

    @Transactional(readOnly = true)
    public List<SpendInsightsResponse.MarketBucket> topMarkets(User user, LocalDateTime from, LocalDateTime to,
                                                               int limit, MarketScope scope) {
        return spend(user, from, to, scope).byMarket().stream().limit(Math.max(0, limit)).toList();
    }

    /**
     * Markets ranked by the discount the household received there, biggest first.
     * Markets that gave no discount are excluded. Reuses the cached {@link #spend}
     * aggregation (gross spend + per-market discount), so it costs nothing extra.
     */
    @Transactional(readOnly = true)
    public List<MarketDiscountResponse> topMarketsByDiscount(User user, LocalDateTime from, LocalDateTime to, int limit) {
        return topMarketsByDiscount(user, from, to, limit, MarketScope.ALL);
    }

    @Transactional(readOnly = true)
    public List<MarketDiscountResponse> topMarketsByDiscount(User user, LocalDateTime from, LocalDateTime to,
                                                             int limit, MarketScope scope) {
        return spend(user, from, to, scope).byMarket().stream()
                .filter(bucket -> bucket.discount() != null && bucket.discount().signum() > 0)
                .sorted(Comparator.comparing(SpendInsightsResponse.MarketBucket::discount).reversed())
                .limit(Math.max(0, limit))
                .map(MarketDiscountResponse::from)
                .toList();
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
