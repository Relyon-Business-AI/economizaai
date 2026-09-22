package com.relyon.economizaai.service.dashboard;

import java.util.Objects;

import com.relyon.economizaai.config.CachingConfig;
import com.relyon.economizaai.dto.response.DashboardResponse;
import com.relyon.economizaai.dto.response.DashboardResponse.SpendSnapshot;
import com.relyon.economizaai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.InsightsService;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
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
import java.time.YearMonth;
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
    private final ReceiptRepository receiptRepository;
    private final ConsumptionIntelligenceService consumptionService;
    private final CommunityPromoService communityPromoService;
    private final WatchedMarketService watchedMarketService;
    private final MarketNameService marketNameService;

    @Transactional(readOnly = true)
    @Cacheable(value = CachingConfig.DASHBOARD_CACHE,
            key = "#user.id + ':' + @householdCacheGen.get(#user.household.id)")
    public DashboardResponse buildCachedDashboard(User user) {
        var householdId = user.getHousehold().getId();
        var spendSnapshot = buildSpendSnapshot(user);
        var recent = loadRecentReceipts(user, householdId);
        var suggested = consumptionService.suggestedList(user, false, 0).items().stream()
                .limit(SUGGESTED_TOP_N)
                .toList();
        var promos = loadTopPromos(user, householdId);

        log.debug("dashboard.core built household={} recent={} suggested={} promos={}",
                householdId, recent.size(), suggested.size(), promos.size());
        // unread is filled live by DashboardService (cheap + must stay fresh).
        return new DashboardResponse(spendSnapshot, recent, suggested, promos, 0L, LocalDateTime.now());
    }

    private SpendSnapshot buildSpendSnapshot(User user) {
        var ym = YearMonth.now();
        var monthStart = ym.atDay(1).atStartOfDay();
        var monthEnd = ym.atEndOfMonth().atTime(23, 59, 59);

        var spendInsights = insightsService.spend(user, monthStart, monthEnd);
        var receiptCount = spendInsights.byMarket().stream()
                .mapToLong(bucket -> bucket.receiptCount())
                .sum();
        var avgTicket = receiptCount > 0
                ? spendInsights.total().divide(BigDecimal.valueOf(receiptCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new SpendSnapshot(ym.getYear(), ym.getMonthValue(),
                spendInsights.total(), spendInsights.totalDiscount(), receiptCount, avgTicket);
    }

    private List<ReceiptSummaryResponse> loadRecentReceipts(User user, UUID householdId) {
        var recentReceipts = receiptRepository
                .findAll(recentForHousehold(user), PageRequest.of(0, RECENT_RECEIPTS,
                        Sort.by(Sort.Direction.DESC, "issuedAt")))
                .getContent();
        var recentCnpjs = recentReceipts.stream()
                .map(Receipt::getCnpjEmitente)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var recentOverrides = marketNameService.resolveNames(householdId, recentCnpjs);
        return recentReceipts.stream()
                .map(receipt -> ReceiptSummaryResponse.from(receipt)
                        .withMarketFriendlyName(marketNameService.applyOverride(
                                recentOverrides, receipt.getCnpjEmitente(), receipt.getMarketName())))
                .toList();
    }

    private List<CommunityPromo> loadTopPromos(User user, UUID householdId) {
        var detectedPromos = communityPromoService.detectAll(
                        user.getHomeLatitude(), user.getHomeLongitude(),
                        null,    // FE typically wants the broad view on the home screen
                        watchedMarketService.watchedCnpjs(user)).stream()
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

    private Specification<Receipt> recentForHousehold(User user) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("household").get("id"), user.getHousehold().getId()),
                cb.equal(root.get("status"), ReceiptStatus.CONFIRMED)
        );
    }
}
