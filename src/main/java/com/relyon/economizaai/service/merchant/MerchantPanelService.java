package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.MerchantPriceComparisonResponse;
import com.relyon.economizaai.dto.response.MerchantPriceComparisonResponse.ProductComparison;
import com.relyon.economizaai.dto.response.MerchantProfileResponse;
import com.relyon.economizaai.dto.response.MerchantProfileResponse.ChainSummary;
import com.relyon.economizaai.dto.response.MerchantProfileResponse.StoreSummary;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ProductStateHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only mini-panel for MERCHANT accounts (docs/MERCHANT_ACCOUNTS.md, Fase 1).
 * Everything is scoped to the chains granted to the logged-in user via
 * merchant_access, and every aggregate obeys the SAME k-anonymity gate as the
 * public index ({@code minHouseholdsForPublic}) — the merchant is a B2B consumer
 * of aggregates, including about its own stores: with few receipts + timestamps,
 * a sub-K "own store" median could be correlated back to a single customer.
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
    private final CollaborativeProperties properties;

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
                .sorted(Comparator.comparing(ProductComparison::productName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        log.info("merchant.price_comparison user={} rows={}", merchantUser.getId(), comparisons.size());
        return new MerchantPriceComparisonResponse(lookbackDays, comparisons);
    }

    private List<String> grantedChains(User merchantUser) {
        return merchantAccessRepository.findAllByUserId(merchantUser.getId()).stream()
                .map(MerchantAccess::getCnpjRoot)
                .distinct()
                .toList();
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
     * One comparison row per (product, state) the chain was observed in: chain
     * median vs. the regional (same-state, whole-index) median. Rows failing the
     * K-gate or the minimum-sample threshold on EITHER side are dropped entirely.
     */
    private List<ProductComparison> chainComparisons(String cnpjRoot, LocalDateTime since) {
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

    private ProductComparison toComparison(ProductStateKey key,
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
        var chainMedian = medianForApi(chainObservations);
        var regionMedian = medianForApi(regionObservations);
        return new ProductComparison(
                key.productId(),
                chainObservations.get(0).getProduct().getNormalizedName(),
                key.state(),
                chainMedian,
                chainObservations.size(),
                regionMedian,
                regionObservations.size(),
                deltaPercent(chainMedian, regionMedian));
    }

    private static BigDecimal medianForApi(List<PriceObservation> observations) {
        var prices = observations.stream().map(PriceObservation::getUnitPrice).toList();
        return PriceIndexService.median(prices).setScale(2, RoundingMode.HALF_UP);
    }

    /** (chain - region) / region in %, one decimal. Positive = chain is more expensive. */
    private static BigDecimal deltaPercent(BigDecimal chainMedian, BigDecimal regionMedian) {
        if (regionMedian.signum() == 0) {
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
}
