package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.HouseholdSavingsResponse;
import com.relyon.economizaai.dto.response.HouseholdSavingsResponse.MarketSavings;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ContributedPrice;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.priceindex.PrivacyTierResolver;
import com.relyon.economizaai.service.savings.ReferencePriceBook.RegionPrice;
import com.relyon.economizaai.service.savings.ReferencePriceBook.Source;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * How much a household saved: each line it bought, at the price really paid, against a
 * reference price for the same product at the same time (see {@link ReferencePriceBook}).
 * A line with no reference, or whose price is implausibly far from it, is left out — the
 * response says how many lines were compared, so the number is never passed off as the whole
 * period.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdSavingsService {

    private static final int DEFAULT_PERIOD_DAYS = 90;

    private final ReceiptItemRepository receiptItemRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final MarketLocationService marketLocationService;
    private final ViewerLocationService viewerLocationService;
    private final MarketNameService marketNameService;
    private final PrivacyTierResolver privacyTierResolver;
    private final SubscriptionGateService subscriptionGate;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public HouseholdSavingsResponse savings(User user, LocalDateTime requestedFrom, LocalDateTime requestedTo) {
        var to = requestedTo != null ? requestedTo : BrazilClock.nowDateTime();
        var from = periodStart(user, requestedFrom, to);
        var householdId = user.getHousehold().getId();

        var ownLines = PurchasedLineFactory.comparableLinesOf(receiptItemRepository
                .findConfirmedInStoreLinesForHousehold(householdId, from.minusDays(settings().ownLookbackDays()), to));
        var linesInPeriod = ownLines.stream().filter(line -> !line.purchasedAt().isBefore(from)).toList();
        var referenceBook = new ReferencePriceBook(
                regionPricesByProduct(user, linesInPeriod, from, to),
                ownLines.stream().collect(Collectors.groupingBy(line -> line.product().getId())),
                privacyTierResolver, settings());
        var comparisons = linesInPeriod.stream()
                .map(line -> compareWithReference(line, referenceBook))
                .flatMap(Optional::stream)
                .toList();

        log.debug("savings.computed household={} lines={} compared={}", householdId, linesInPeriod.size(), comparisons.size());
        return toResponse(householdId, from, to, linesInPeriod.size(), comparisons);
    }

    /** FREE history window first; with no start asked for, the last {@value #DEFAULT_PERIOD_DAYS} days. */
    private LocalDateTime periodStart(User user, LocalDateTime requestedFrom, LocalDateTime to) {
        var clampedFrom = subscriptionGate.clampFrom(user, requestedFrom);
        return clampedFrom != null ? clampedFrom : to.minusDays(DEFAULT_PERIOD_DAYS);
    }

    private ReferencePriceBook.Settings settings() {
        var collaborative = properties.getCollaborative();
        return new ReferencePriceBook.Settings(collaborative.getSavingsRegionWindowDays(),
                collaborative.getSavingsMinRegionSamples(), collaborative.getSavingsOwnLookbackDays(),
                collaborative.getSavingsMinOwnSamples());
    }

    /** What OTHER households paid, near the buyer, for the products bought in the period. */
    private Map<UUID, List<RegionPrice>> regionPricesByProduct(User user, List<PurchasedLine> linesInPeriod,
                                                               LocalDateTime from, LocalDateTime to) {
        if (!properties.getCollaborative().isEnabled() || linesInPeriod.isEmpty()) return Map.of();
        var productIds = linesInPeriod.stream().map(line -> line.product().getId()).collect(Collectors.toSet());
        var windowDays = settings().regionWindowDays();
        var householdId = user.getHousehold().getId();
        var pricesFromOthers = auditRepository
                .findContributedPrices(productIds, from.toLocalDate().minusDays(windowDays).atStartOfDay(), to.plusDays(windowDays))
                .stream()
                .filter(contributedPrice -> !householdId.equals(contributedPrice.getHouseholdId()))
                .toList();
        var storesByCnpj = marketLocationService.findByCnpjs(
                pricesFromOthers.stream().map(ContributedPrice::getMarketCnpj).distinct().toList());
        var buyerLocation = viewerLocationService.resolve(user, null, null);
        return pricesFromOthers.stream()
                .filter(contributedPrice -> isInBuyerRegion(contributedPrice, storesByCnpj.get(contributedPrice.getMarketCnpj()), buyerLocation))
                .collect(Collectors.groupingBy(ContributedPrice::getProductId, Collectors.mapping(
                        contributedPrice -> toRegionPrice(contributedPrice, storesByCnpj.get(contributedPrice.getMarketCnpj())),
                        Collectors.toList())));
    }

    /** Within the "perto de você" radius when the buyer has a location; else the buyer's state; else nowhere. */
    private boolean isInBuyerRegion(ContributedPrice contributedPrice, MarketLocation store, ViewerLocation buyerLocation) {
        if (buyerLocation.hasCoordinates()) {
            return store != null && store.hasCoordinates()
                    && DistanceCalculator.kmBetween(buyerLocation.latitude(), buyerLocation.longitude(),
                            store.getLatitude(), store.getLongitude())
                    <= properties.getCollaborative().getBestMarketsNearbyRadiusKm();
        }
        return buyerLocation.uf() != null && buyerLocation.uf().equals(contributedPrice.getState());
    }

    private RegionPrice toRegionPrice(ContributedPrice contributedPrice, MarketLocation store) {
        return new RegionPrice(contributedPrice.getUnitPrice(), contributedPrice.getObservedAt().toLocalDate(),
                contributedPrice.getHouseholdId(), PrivacyTierResolver.isSensitiveMarket(store));
    }

    private Optional<LineComparison> compareWithReference(PurchasedLine line, ReferencePriceBook referenceBook) {
        return referenceBook.referenceFor(line)
                .filter(reference -> isPlausible(line.paidUnitPrice(), reference.unitPrice()))
                .map(reference -> new LineComparison(line, reference.source(),
                        reference.unitPrice().subtract(line.paidUnitPrice())
                                .multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP)));
    }

    /** A price several times above or below its reference is a mismatched product or unit, not a saving. */
    private boolean isPlausible(BigDecimal paidUnitPrice, BigDecimal referenceUnitPrice) {
        if (referenceUnitPrice == null || referenceUnitPrice.signum() <= 0 || paidUnitPrice.signum() <= 0) return false;
        var maxRatio = properties.getCollaborative().getSavingsMaxPriceRatio();
        var ratio = paidUnitPrice.doubleValue() / referenceUnitPrice.doubleValue();
        return ratio <= maxRatio && ratio >= 1 / maxRatio;
    }

    private HouseholdSavingsResponse toResponse(UUID householdId, LocalDateTime from, LocalDateTime to,
                                                int purchasedLineCount, List<LineComparison> comparisons) {
        return new HouseholdSavingsResponse(from, to,
                sum(comparisons, LineComparison::saved),
                sum(comparisons, LineComparison::paidAboveReference),
                sum(comparisons, comparison -> comparison.line().paidTotal()),
                purchasedLineCount,
                comparisons.size(),
                countBySource(comparisons, Source.REGION),
                countBySource(comparisons, Source.HOUSEHOLD),
                countWithAmount(comparisons, LineComparison::saved),
                countWithAmount(comparisons, LineComparison::paidAboveReference),
                marketSavings(householdId, comparisons));
    }

    private List<MarketSavings> marketSavings(UUID householdId, List<LineComparison> comparisons) {
        var comparisonsByCnpj = comparisons.stream()
                .filter(comparison -> comparison.line().marketCnpj() != null)
                .collect(Collectors.groupingBy(comparison -> comparison.line().marketCnpj()));
        var cnpjs = comparisonsByCnpj.keySet();
        var nameOverrides = marketNameService.resolveNames(householdId, cnpjs);
        var chainNames = marketNameService.resolveChainNames(cnpjs);
        return comparisonsByCnpj.entrySet().stream()
                .map(entry -> toMarketSavings(entry.getKey(), entry.getValue(), nameOverrides, chainNames))
                .sorted(Comparator.comparing(MarketSavings::saved).reversed())
                .toList();
    }

    private MarketSavings toMarketSavings(String cnpj, List<LineComparison> comparisonsAtMarket,
                                          Map<String, String> nameOverrides, Map<String, String> chainNames) {
        var marketName = comparisonsAtMarket.stream()
                .map(comparison -> comparison.line().marketName())
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        return new MarketSavings(cnpj, marketName,
                marketNameService.friendlyName(nameOverrides, chainNames, cnpj, marketName),
                sum(comparisonsAtMarket, LineComparison::saved),
                sum(comparisonsAtMarket, LineComparison::paidAboveReference),
                sum(comparisonsAtMarket, comparison -> comparison.line().paidTotal()),
                comparisonsAtMarket.size());
    }

    private static BigDecimal sum(List<LineComparison> comparisons, Function<LineComparison, BigDecimal> amountOf) {
        return comparisons.stream().map(amountOf).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static int countWithAmount(List<LineComparison> comparisons, Function<LineComparison, BigDecimal> amountOf) {
        return (int) comparisons.stream().filter(comparison -> amountOf.apply(comparison).signum() > 0).count();
    }

    private static int countBySource(List<LineComparison> comparisons, Source source) {
        return (int) comparisons.stream().filter(comparison -> comparison.source() == source).count();
    }
}
