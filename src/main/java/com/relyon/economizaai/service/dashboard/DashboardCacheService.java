package com.relyon.economizaai.service.dashboard;

import com.relyon.economizaai.time.BrazilClock;
import java.util.Objects;

import com.relyon.economizaai.config.CachingConfig;
import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizaai.dto.response.DashboardResponse;
import com.relyon.economizaai.dto.response.DashboardResponse.SpendSnapshot;
import com.relyon.economizaai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.model.User;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import com.relyon.economizaai.service.InsightsService;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.NearbyScopeService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService.CommunityPromo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The cacheable, expensive part of the dashboard: spend snapshot + recent
 * receipts + suggested list + community promos. Everything here is
 * household-scoped (or stable per user), so it's cached under
 * {@code user.id : householdGen}. The cheap, must-be-live unread count is
 * composed on top by {@link DashboardService} and deliberately NOT cached.
 *
 * <p>Lives in its own bean so the {@code @Cacheable} proxy actually applies
 * (a self-invocation from DashboardService would bypass the cache). The
 * returned {@link DashboardResponse} carries {@code unreadNotificationCount=0}
 * as a placeholder — DashboardService overwrites it with the live value.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardCacheService {

    private static final int RECENT_RECEIPTS = 5;
    private static final int SUGGESTED_TOP_N = 5;
    private static final int PROMOS_TOP_N = 5;

    private final InsightsService insightsService;
    private final InsightsRepository insightsRepository;
    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;
    private final ConsumptionIntelligenceService consumptionService;
    private final CommunityPromoService communityPromoService;
    private final NearbyScopeService nearbyScopeService;
    private final MarketNameService marketNameService;

    /** Backwards-compatible entry point — every segment (ALL scope). */
    @Transactional(readOnly = true)
    public DashboardResponse buildCachedDashboard(User user) {
        return buildCachedDashboard(user, MarketScope.ALL);
    }

    // Cached here too: the delegation below is a self-invocation, which the caching proxy
    // can't see — without this annotation the full variant would never hit the cache.
    @Transactional(readOnly = true)
    @Cacheable(value = CachingConfig.DASHBOARD_CACHE,
            key = "#user.id + ':' + #scope + ':false:' + @householdCacheGen.get(#user.household.id)")
    public DashboardResponse buildCachedDashboard(User user, MarketScope scope) {
        return buildCachedDashboard(user, scope, false);
    }

    /**
     * {@code light} skips consumption predictions and nearby promos — the two sections
     * that dominate the build time and that the app's Home doesn't render. Cached under
     * its own key so a light hit never hands a full client empty sections.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = CachingConfig.DASHBOARD_CACHE,
            key = "#user.id + ':' + #scope + ':' + #light + ':' + @householdCacheGen.get(#user.household.id)")
    public DashboardResponse buildCachedDashboard(User user, MarketScope scope, boolean light) {
        var householdId = user.getHousehold().getId();
        var spendSnapshot = buildSpendSnapshot(user, scope);
        var recent = loadRecentReceipts(user, householdId, scope);
        List<ConsumptionPredictionResponse> suggested = List.of();
        List<CommunityPromo> promos = List.of();
        if (!light) {
            suggested = consumptionService.suggestedList(user, false, 0).items().stream()
                    .limit(SUGGESTED_TOP_N)
                    .toList();
            promos = loadTopPromos(user, householdId);
        }

        log.debug("dashboard.core built household={} light={} recent={} suggested={} promos={}",
                householdId, light, recent.size(), suggested.size(), promos.size());
        // unread is filled live by DashboardService (cheap + must stay fresh).
        return new DashboardResponse(spendSnapshot, recent, suggested, promos, 0L, LocalDateTime.now());
    }

    private SpendSnapshot buildSpendSnapshot(User user, MarketScope scope) {
        var ym = BrazilClock.currentYearMonth();
        var monthStart = ym.atDay(1).atStartOfDay();
        var monthEnd = ym.atEndOfMonth().atTime(23, 59, 59);

        var spendInsights = insightsService.spend(user, monthStart, monthEnd, scope);
        var receiptCount = spendInsights.byMarket().stream()
                .mapToLong(bucket -> bucket.receiptCount())
                .sum();
        var avgTicket = receiptCount > 0
                ? spendInsights.total().divide(BigDecimal.valueOf(receiptCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new SpendSnapshot(ym.getYear(), ym.getMonthValue(),
                spendInsights.total(), spendInsights.totalDiscount(), receiptCount, avgTicket);
    }

    private List<ReceiptSummaryResponse> loadRecentReceipts(User user, UUID householdId, MarketScope scope) {
        var recentReceipts = receiptRepository
                .findAll(recentForHousehold(user, scope), PageRequest.of(0, RECENT_RECEIPTS,
                        Sort.by(Sort.Direction.DESC, "issuedAt")))
                .getContent();
        var recentCnpjs = recentReceipts.stream()
                .map(Receipt::getCnpjEmitente)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var recentOverrides = marketNameService.resolveNames(householdId, recentCnpjs);
        var scannerNames = scannerNamesFor(recentReceipts);
        return recentReceipts.stream()
                .map(receipt -> ReceiptSummaryResponse.from(receipt)
                        .withMarketFriendlyName(marketNameService.applyOverride(
                                recentOverrides, receipt.getCnpjEmitente(), receipt.getMarketName()))
                        .withScannedByName(scannerNames.get(scannerId(receipt))))
                .toList();
    }

    private static UUID scannerId(Receipt receipt) {
        return receipt.getUser() == null ? null : receipt.getUser().getId();
    }

    // One query for the scanners' names ("por Ana" on the home list) instead of a lazy load per row.
    private Map<UUID, String> scannerNamesFor(List<Receipt> receipts) {
        var userIds = receipts.stream()
                .map(DashboardCacheService::scannerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // HashMap, not Map.of(): the lookup key may be null for a user-less row, and Map.of().get(null) throws.
        if (userIds.isEmpty()) return new HashMap<>();
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName, (first, second) -> first, HashMap::new));
    }

    private List<CommunityPromo> loadTopPromos(User user, UUID householdId) {
        // "perto de você" — a deal 2 000 km away is noise on the home screen.
        var detectedPromos = communityPromoService.detectInScope(nearbyScopeService.forUser(user)).stream()
                .limit(PROMOS_TOP_N)
                .toList();
        var promoOverrides = marketNameService.resolveNames(householdId, detectedPromos.stream()
                .map(promo -> promo.marketCnpj())
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return detectedPromos.stream()
                .map(promo -> promo.withMarketFriendlyName(marketNameService.applyOverride(
                        promoOverrides, promo.marketCnpj(), promo.marketName())))
                .toList();
    }

    private Specification<Receipt> recentForHousehold(User user, MarketScope scope) {
        var householdId = user.getHousehold().getId();
        return (root, query, cb) -> {
            var base = cb.and(
                    cb.equal(root.get("household").get("id"), householdId),
                    cb.equal(root.get("status"), ReceiptStatus.CONFIRMED));
            if (scope == MarketScope.ALL) return base;
            var scopeCnpjs = insightsRepository.supportedCnpjs(householdId, scope.segments(),
                    scope.absorbsSupportOverride());
            if (scopeCnpjs.isEmpty()) {
                // No CNPJs in the lens's segments yet: inclusion shows nothing, exclusion shows all.
                return scope.inclusion() ? cb.and(base, cb.disjunction()) : base;
            }
            var inScope = root.get("cnpjEmitente").in(scopeCnpjs);
            return scope.inclusion()
                    ? cb.and(base, inScope)
                    : cb.and(base, cb.or(root.get("cnpjEmitente").isNull(), cb.not(inScope)));
        };
    }
}
