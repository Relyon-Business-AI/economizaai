package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.MerchantAnalyticsResponse;
import com.relyon.economizaai.dto.response.MerchantAnalyticsResponse.ActivityPoint;
import com.relyon.economizaai.dto.response.MerchantBrandShareResponse;
import com.relyon.economizaai.dto.response.MerchantBrandShareResponse.BrandShare;
import com.relyon.economizaai.dto.response.MerchantCategoryBenchmarkResponse;
import com.relyon.economizaai.dto.response.MerchantCategoryBenchmarkResponse.CategoryBenchmark;
import com.relyon.economizaai.dto.response.MerchantCompetitorMovesResponse;
import com.relyon.economizaai.dto.response.MerchantCompetitorMovesResponse.CompetitorMove;
import com.relyon.economizaai.dto.response.MerchantPriceComparisonResponse;
import com.relyon.economizaai.dto.response.MerchantPriceComparisonResponse.ProductComparison;
import com.relyon.economizaai.dto.response.MerchantProfileResponse;
import com.relyon.economizaai.dto.response.MerchantProfileResponse.ChainSummary;
import com.relyon.economizaai.dto.response.MerchantProfileResponse.StoreSummary;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.BrandDemandCount;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ProductStateHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ReceiptRepository.ReceiptActivityRow;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only B2B data panel for MERCHANT accounts (docs/MERCHANT_ACCOUNTS.md).
 * Everything is scoped to the chains granted to the logged-in user via
 * merchant_access, and every aggregate that discloses OTHER households' data
 * obeys the SAME k-anonymity gate as the public index — the FULL K
 * ({@code minHouseholdsForPublic}) AND the minimum-sample floor, UNCONDITIONALLY
 * (never the relaxed grocery n=1): a merchant is a B2B list-shaped consumer. The
 * merchant's OWN-store totals ({@link #analytics}) are its own data and are not
 * K-gated; every per-product / brand / competitor read is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantPanelService {

    private final MerchantAccessRepository merchantAccessRepository;
    private final MarketLocationRepository marketLocationRepository;
    private final ReceiptRepository receiptRepository;
    private final PriceObservationRepository observationRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final CategoryResolver categoryResolver;
    private final CollaborativeProperties properties;

    private static final int MAX_WINDOW_DAYS = 365;
    private static final int WEEKLY_THRESHOLD_DAYS = 92;

    @Transactional(readOnly = true)
    public MerchantProfileResponse profile(User merchantUser) {
        var chains = grantedChains(merchantUser).stream()
                .map(this::chainSummary)
                .toList();
        log.info("merchant.profile user={} chains={}", merchantUser.getId(), chains.size());
        return new MerchantProfileResponse(chains);
    }

    @Transactional(readOnly = true)
    public MerchantPriceComparisonResponse priceComparison(User merchantUser) {
        var lookbackDays = properties.getCollaborative().getLookbackDays();
        var since = BrazilClock.nowDateTime().minusDays(lookbackDays);
        var comparisons = grantedChains(merchantUser).stream()
                .flatMap(cnpjRoot -> chainComparisons(cnpjRoot, since).stream())
                .map(EnrichedComparison::comparison)
                .sorted(Comparator.comparing(ProductComparison::productName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        log.info("merchant.price_comparison user={} rows={}", merchantUser.getId(), comparisons.size());
        return new MerchantPriceComparisonResponse(lookbackDays, comparisons);
    }

    /**
     * Per-category benchmark: the chain's price index vs. the regional (same-state) index per
     * taxonomy GROUP, rolled up ONLY from (product, state) rows that each passed the full K=3 gate
     * on both sides. Nothing sub-K ever contributes.
     */
    @Transactional(readOnly = true)
    public MerchantCategoryBenchmarkResponse categoryBenchmark(User merchantUser, Integer window) {
        var days = effectiveWindow(window);
        var since = BrazilClock.nowDateTime().minusDays(days);
        var locale = LocaleContextHolder.getLocale();
        var byCategoryState = grantedChains(merchantUser).stream()
                .flatMap(cnpjRoot -> chainComparisons(cnpjRoot, since).stream())
                .collect(Collectors.groupingBy(enriched ->
                        new CategoryStateKey(enriched.categoryGroupId(), enriched.comparison().state())));
        var categories = byCategoryState.entrySet().stream()
                .map(entry -> toCategoryBenchmark(entry.getKey(), entry.getValue(), locale))
                .sorted(Comparator.comparing(CategoryBenchmark::categoryName,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CategoryBenchmark::state, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        log.info("merchant.category_benchmark user={} window={} rows={}", merchantUser.getId(), days, categories.size());
        return new MerchantCategoryBenchmarkResponse(days, categories);
    }

    /**
     * Anonymized demand signal by brand across the merchant's region (the states its chain operates
     * in), over the window, optionally narrowed to a category slug. K=3: a brand is disclosed only
     * when at least K distinct households contributed (enforced in the DB via HAVING).
     */
    @Transactional(readOnly = true)
    public MerchantBrandShareResponse brandShare(User merchantUser, String categorySlug, Integer window) {
        var days = effectiveWindow(window);
        var since = BrazilClock.nowDateTime().minusDays(days);
        var states = regionStates(merchantUser, since);
        if (states.isEmpty()) {
            return new MerchantBrandShareResponse(days, categorySlug, states, List.of());
        }
        var minHouseholds = properties.getCollaborative().getMinHouseholdsForPublic();
        var demand = brandDemand(categorySlug, states, since, minHouseholds);
        var totalObservations = demand.stream().mapToLong(BrandDemandCount::getObservations).sum();
        var brands = demand.stream()
                .sorted(Comparator.comparingLong(BrandDemandCount::getObservations).reversed())
                .map(count -> new BrandShare(count.getBrand(), count.getObservations(), count.getHouseholds(),
                        sharePercent(count.getObservations(), totalObservations)))
                .toList();
        log.info("merchant.brand_share user={} window={} category={} brands={}",
                merchantUser.getId(), days, categorySlug, brands.size());
        return new MerchantBrandShareResponse(days, categorySlug, states, brands);
    }

    /**
     * The merchant's OWN stores' activity over time (receipts, revenue, basket-average), bucketed
     * daily or weekly. Own data → not K-gated. Only aggregate time buckets are exposed; there is no
     * per-product or per-customer breakdown here.
     */
    @Transactional(readOnly = true)
    public MerchantAnalyticsResponse analytics(User merchantUser, Integer window) {
        var days = effectiveWindow(window);
        var since = BrazilClock.nowDateTime().minusDays(days);
        var cnpjs = grantedChains(merchantUser).stream()
                .flatMap(cnpjRoot -> marketLocationRepository.findAllByCnpjRoot(cnpjRoot).stream())
                .map(MarketLocation::getCnpj)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var weekly = days > WEEKLY_THRESHOLD_DAYS;
        if (cnpjs.isEmpty()) {
            return new MerchantAnalyticsResponse(days, weekly ? "WEEKLY" : "DAILY", 0,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), null, List.of());
        }
        var rows = receiptRepository.findConfirmedActivityForCnpjs(cnpjs, since);
        var series = buildSeries(rows, weekly);
        var totalReceipts = rows.size();
        var totalRevenue = rows.stream()
                .map(row -> row.getTotalAmount() == null ? BigDecimal.ZERO : row.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        log.info("merchant.analytics user={} window={} stores={} receipts={}",
                merchantUser.getId(), days, cnpjs.size(), totalReceipts);
        return new MerchantAnalyticsResponse(days, weekly ? "WEEKLY" : "DAILY", totalReceipts, totalRevenue,
                basketAverage(totalRevenue, totalReceipts), series);
    }

    /**
     * Anonymized feed of notable price moves by OTHER stores in the merchant's region, for products
     * the merchant sells. NEVER names the competitor, its CNPJ or its live price — only product,
     * direction, magnitude% and approximate recency. K=3 on the recent (other-store) side.
     */
    @Transactional(readOnly = true)
    public MerchantCompetitorMovesResponse competitorMoves(User merchantUser, Integer window) {
        var collaborative = properties.getCollaborative();
        var days = effectiveWindow(window);
        var recentWindowDays = collaborative.getCommunityPromoRecentWindowDays();
        var roots = grantedChains(merchantUser);
        var since = BrazilClock.nowDateTime().minusDays(days);
        var recentSince = BrazilClock.nowDateTime().minusDays(recentWindowDays);

        var chainObservations = roots.stream()
                .flatMap(cnpjRoot -> observationRepository.findRecentByMarketCnpjRoot(cnpjRoot, since).stream())
                .filter(observation -> observation.getState() != null)
                .toList();
        var productIds = chainObservations.stream()
                .map(observation -> observation.getProduct().getId()).distinct().toList();
        var states = chainObservations.stream().map(PriceObservation::getState).distinct().toList();
        if (productIds.isEmpty() || states.isEmpty() || roots.isEmpty()) {
            return new MerchantCompetitorMovesResponse(days, recentWindowDays, List.of());
        }

        var otherByProductState = observationRepository
                .findRecentByProductIdsAndStatesExcludingChains(productIds, states, roots, since).stream()
                .filter(observation -> observation.getState() != null)
                .collect(Collectors.groupingBy(ProductStateKey::of));
        var recentHouseholds = householdCountsByKey(auditRepository
                .countDistinctHouseholdsPerProductStateExcludingChains(productIds, states, roots, recentSince));

        var moves = otherByProductState.entrySet().stream()
                .map(entry -> toCompetitorMove(entry.getKey(), entry.getValue(),
                        recentHouseholds.getOrDefault(entry.getKey(), 0L), recentSince))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CompetitorMove::magnitudePercent).reversed())
                .toList();
        log.info("merchant.competitor_moves user={} window={} moves={}", merchantUser.getId(), days, moves.size());
        return new MerchantCompetitorMovesResponse(days, recentWindowDays, moves);
    }

    private List<String> grantedChains(User merchantUser) {
        return merchantAccessRepository.findAllByUserId(merchantUser.getId()).stream()
                .map(MerchantAccess::getCnpjRoot)
                .distinct()
                .toList();
    }

    /** Distinct states the merchant's chains were observed in over the window — its "region". */
    private List<String> regionStates(User merchantUser, LocalDateTime since) {
        return grantedChains(merchantUser).stream()
                .flatMap(cnpjRoot -> observationRepository.findRecentByMarketCnpjRoot(cnpjRoot, since).stream())
                .map(PriceObservation::getState)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<BrandDemandCount> brandDemand(String categorySlug, List<String> states,
                                               LocalDateTime since, long minHouseholds) {
        if (categorySlug == null || categorySlug.isBlank()) {
            return auditRepository.brandDemandInStates(states, since, minHouseholds);
        }
        var categoryIds = List.copyOf(categoryResolver.nodeAndDescendantIds(categorySlug));
        if (categoryIds.isEmpty()) {
            return List.of();
        }
        return auditRepository.brandDemandInStatesForCategories(states, categoryIds, since, minHouseholds);
    }

    private ChainSummary chainSummary(String cnpjRoot) {
        var stores = marketLocationRepository.findAllByCnpjRoot(cnpjRoot).stream()
                .map(location -> StoreSummary.from(location,
                        receiptRepository.countByCnpjEmitente(location.getCnpj())))
                .sorted(Comparator.comparingLong(StoreSummary::receiptCount).reversed())
                .toList();
        return new ChainSummary(cnpjRoot, stores);
    }

    /**
     * One enriched comparison per (product, state) the chain was observed in: chain median vs. the
     * regional (same-state, whole-index) median, plus the product's category group for roll-up.
     * Rows failing the K-gate or the minimum-sample threshold on EITHER side are dropped entirely.
     */
    private List<EnrichedComparison> chainComparisons(String cnpjRoot, LocalDateTime since) {
        var chainByProductState = observationRepository.findRecentByMarketCnpjRoot(cnpjRoot, since).stream()
                .filter(observation -> observation.getState() != null)
                .collect(Collectors.groupingBy(ProductStateKey::of));
        if (chainByProductState.isEmpty()) {
            return List.of();
        }

        var productIds = chainByProductState.keySet().stream().map(ProductStateKey::productId).distinct().toList();
        var states = chainByProductState.keySet().stream().map(ProductStateKey::state).distinct().toList();

        var regionByProductState = observationRepository
                .findRecentByProductIdsAndStates(productIds, states, since).stream()
                .filter(observation -> observation.getState() != null)
                .collect(Collectors.groupingBy(ProductStateKey::of));
        var chainHouseholds = householdCountsByKey(
                auditRepository.countDistinctHouseholdsPerProductStateForChain(cnpjRoot, since));
        var regionHouseholds = householdCountsByKey(
                auditRepository.countDistinctHouseholdsPerProductState(productIds, states, since));

        return chainByProductState.entrySet().stream()
                .map(entry -> toComparison(entry.getKey(), entry.getValue(),
                        regionByProductState.getOrDefault(entry.getKey(), List.of()),
                        chainHouseholds.getOrDefault(entry.getKey(), 0L),
                        regionHouseholds.getOrDefault(entry.getKey(), 0L)))
                .filter(Objects::nonNull)
                .toList();
    }

    private static Map<ProductStateKey, Long> householdCountsByKey(List<ProductStateHouseholdCount> counts) {
        return counts.stream().collect(Collectors.toMap(
                count -> new ProductStateKey(count.getProductId(), count.getState()),
                ProductStateHouseholdCount::getHouseholds));
    }

    private EnrichedComparison toComparison(ProductStateKey key,
                                            List<PriceObservation> chainObservations,
                                            List<PriceObservation> regionObservations,
                                            long chainHouseholds, long regionHouseholds) {
        var collaborative = properties.getCollaborative();
        var belowSampleFloor = chainObservations.size() < collaborative.getMinObservationsPerProductMarket()
                || regionObservations.size() < collaborative.getMinObservationsPerProductMarket();
        var belowKAnon = chainHouseholds < collaborative.getMinHouseholdsForPublic()
                || regionHouseholds < collaborative.getMinHouseholdsForPublic();
        if (belowSampleFloor || belowKAnon) {
            return null;
        }
        var product = chainObservations.get(0).getProduct();
        var chainMedian = medianForApi(chainObservations);
        var regionMedian = medianForApi(regionObservations);
        var comparison = new ProductComparison(
                key.productId(),
                product.getNormalizedName(),
                key.state(),
                chainMedian,
                chainObservations.size(),
                regionMedian,
                regionObservations.size(),
                deltaPercent(chainMedian, regionMedian));
        return new EnrichedComparison(comparison, categoryResolver.groupIdOf(product.getCategoryId()));
    }

    private CategoryBenchmark toCategoryBenchmark(CategoryStateKey key, List<EnrichedComparison> rows,
                                                  Locale locale) {
        var chainMedians = rows.stream().map(row -> row.comparison().chainMedianPrice()).toList();
        var regionMedians = rows.stream().map(row -> row.comparison().regionMedianPrice()).toList();
        var deltas = rows.stream()
                .map(row -> row.comparison().deltaPercent())
                .filter(Objects::nonNull)
                .toList();
        return new CategoryBenchmark(
                key.categoryGroupId(),
                categoryResolver.slugForId(key.categoryGroupId()),
                categoryResolver.labelFor(key.categoryGroupId(), locale),
                key.state(),
                scale2(PriceIndexService.median(chainMedians)),
                scale2(PriceIndexService.median(regionMedians)),
                scale1(PriceIndexService.median(deltas)),
                rows.size());
    }

    private CompetitorMove toCompetitorMove(ProductStateKey key, List<PriceObservation> observations,
                                            long recentHouseholds, LocalDateTime recentSince) {
        var collaborative = properties.getCollaborative();
        if (recentHouseholds < collaborative.getMinHouseholdsForPublic()) {
            return null;
        }
        var recent = observations.stream()
                .filter(observation -> !observation.getObservedAt().isBefore(recentSince)).toList();
        var baseline = observations.stream()
                .filter(observation -> observation.getObservedAt().isBefore(recentSince)).toList();
        if (recent.size() < collaborative.getCommunityPromoMinRecentObservations()
                || baseline.size() < collaborative.getMinObservationsPerProductMarket()) {
            return null;
        }
        var recentMedian = medianForApi(recent);
        var baselineMedian = medianForApi(baseline);
        var delta = deltaPercent(recentMedian, baselineMedian);
        if (delta == null || delta.abs().compareTo(BigDecimal.valueOf(collaborative.getCommunityPromoThresholdPct())) < 0) {
            return null;
        }
        var latest = recent.stream().map(PriceObservation::getObservedAt).max(LocalDateTime::compareTo).orElseThrow();
        var approxDaysAgo = (int) ChronoUnit.DAYS.between(latest.toLocalDate(), BrazilClock.today());
        return new CompetitorMove(
                key.productId(),
                observations.get(0).getProduct().getNormalizedName(),
                key.state(),
                delta.signum() < 0 ? "DOWN" : "UP",
                delta.abs(),
                Math.max(approxDaysAgo, 0));
    }

    private List<ActivityPoint> buildSeries(List<ReceiptActivityRow> rows, boolean weekly) {
        var byBucket = rows.stream().collect(Collectors.groupingBy(row -> bucketStart(row.getIssuedAt(), weekly)));
        return byBucket.entrySet().stream()
                .map(entry -> {
                    var bucketRows = entry.getValue();
                    var revenue = bucketRows.stream()
                            .map(row -> row.getTotalAmount() == null ? BigDecimal.ZERO : row.getTotalAmount())
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .setScale(2, RoundingMode.HALF_UP);
                    return new ActivityPoint(entry.getKey(), bucketRows.size(), revenue,
                            basketAverage(revenue, bucketRows.size()));
                })
                .sorted(Comparator.comparing(ActivityPoint::periodStart))
                .toList();
    }

    private static LocalDate bucketStart(LocalDateTime issuedAt, boolean weekly) {
        var day = issuedAt.toLocalDate();
        return weekly ? day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : day;
    }

    private static BigDecimal basketAverage(BigDecimal revenue, long receipts) {
        if (receipts == 0) {
            return null;
        }
        return revenue.divide(BigDecimal.valueOf(receipts), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sharePercent(long observations, long total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(observations)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private int effectiveWindow(Integer window) {
        if (window == null || window <= 0) {
            return properties.getCollaborative().getLookbackDays();
        }
        return Math.min(window, MAX_WINDOW_DAYS);
    }

    private static BigDecimal medianForApi(List<PriceObservation> observations) {
        var prices = observations.stream().map(PriceObservation::getUnitPrice).toList();
        return scale2(PriceIndexService.median(prices));
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale1(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    /** (chain - region) / region in %, one decimal. Positive = chain is more expensive. */
    private static BigDecimal deltaPercent(BigDecimal chainMedian, BigDecimal regionMedian) {
        if (chainMedian == null || regionMedian == null || regionMedian.signum() == 0) {
            return null;
        }
        return chainMedian.subtract(regionMedian)
                .divide(regionMedian, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }

    private record ProductStateKey(UUID productId, String state) {
        static ProductStateKey of(PriceObservation observation) {
            return new ProductStateKey(observation.getProduct().getId(), observation.getState());
        }
    }

    private record CategoryStateKey(UUID categoryGroupId, String state) {}

    private record EnrichedComparison(ProductComparison comparison, UUID categoryGroupId) {}
}
