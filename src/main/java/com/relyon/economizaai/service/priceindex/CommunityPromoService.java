package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.MarketLocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Community-wide promo detection.
 *
 * <p>Algorithm per (product, market):
 * <ol>
 *   <li>Load all observations for product in the last
 *       {@link CollaborativeProperties.Collaborative#getLookbackDays()} days.</li>
 *   <li>Split into "recent" (last 7 days) and "baseline" (the rest).</li>
 *   <li>If recent median is more than
 *       {@link CollaborativeProperties.Collaborative#getCommunityPromoThresholdPct()}%
 *       below baseline median AND both samples meet k-anonymity,
 *       it's a community promo.</li>
 * </ol>
 *
 * <p>Stateless / on-demand for V1 (no community_promos table yet — added
 * later when notifications need to react to promo events).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityPromoService {

    private final PriceObservationRepository observationRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final CollaborativeProperties properties;
    private final MarketLocationService marketLocationService;

    // Self-reference so the no-arg overload's call to the @Transactional
    // detectAll(...) goes through the Spring proxy. Defaults to `this` for tests.
    @Lazy
    @Autowired
    private CommunityPromoService self = this;

    /** Backwards-compat overload used by tests + scheduled jobs (no distance filter). */
    public List<CommunityPromo> detectAll() {
        return self.detectAll(null, null, null, Set.of());
    }

    @Transactional(readOnly = true)
    public List<CommunityPromo> detectAll(BigDecimal userLatitude,
                                          BigDecimal userLongitude,
                                          Double radiusKm,
                                          Set<String> watchedCnpjs) {
        if (!properties.getCollaborative().isEnabled()) return List.of();
        var watched = watchedCnpjs == null ? Set.<String>of() : watchedCnpjs;

        var since = LocalDateTime.now().minusDays(properties.getCollaborative().getLookbackDays());
        var observations = observationRepository.findRecent(since);
        if (observations.isEmpty()) return List.of();

        var locations = (userLatitude != null && userLongitude != null)
                ? marketLocationService.findByCnpjs(observations.stream()
                        .map(PriceObservation::getMarketCnpj).distinct().toList())
                : Map.<String, MarketLocation>of();

        // Group by (productId, marketCnpj)
        Map<UUID, Map<String, List<PriceObservation>>> byProductMarket = observations.stream()
                .collect(Collectors.groupingBy(
                        po -> po.getProduct().getId(),
                        Collectors.groupingBy(PriceObservation::getMarketCnpj)));

        var promos = new ArrayList<CommunityPromo>();
        var recentCutoff = LocalDateTime.now().minusDays(properties.getCollaborative().getCommunityPromoRecentWindowDays());

        for (var productEntry : byProductMarket.entrySet()) {
            var productId = productEntry.getKey();
            for (var marketEntry : productEntry.getValue().entrySet()) {
                var promo = detectPromoForMarket(productId, marketEntry.getKey(), marketEntry.getValue(),
                        since, recentCutoff, locations, watched, userLatitude, userLongitude, radiusKm);
                if (promo != null) promos.add(promo);
            }
        }
        promos.sort(Comparator.comparing(CommunityPromo::dropPct).reversed());
        return promos;
    }

    /**
     * Detect a community promo for one (product, market) group, or null if it
     * doesn't qualify (too few observations, fails k-anonymity, out of radius, or
     * the recent median isn't below the baseline threshold). Extracted from
     * detectAll to keep that method's cognitive complexity within bounds (S3776).
     */
    private CommunityPromo detectPromoForMarket(UUID productId, String marketCnpj, List<PriceObservation> rows,
                                                LocalDateTime since, LocalDateTime recentCutoff,
                                                Map<String, MarketLocation> locations, Set<String> watched,
                                                BigDecimal userLatitude, BigDecimal userLongitude, Double radiusKm) {
        var distinctHouseholds = distinctHouseholdsIfSampleGatePasses(productId, marketCnpj, rows, since);
        if (distinctHouseholds == null) return null;

        var isWatched = watched.contains(marketCnpj);
        var geoEligibility = checkGeoEligibility(marketCnpj, locations, isWatched, userLatitude, userLongitude, radiusKm);
        if (geoEligibility == null) return null;

        var priceDrop = computePriceDrop(rows, recentCutoff);
        if (priceDrop == null) return null;

        var firstRow = rows.get(0);
        log.info("community_promo.detected product={} market={} recent={} baseline={} dropPct={} samples={} households={}",
                productId, marketCnpj, priceDrop.recentMedian(), priceDrop.baselineMedian(), priceDrop.dropPct(),
                priceDrop.recentSampleCount(), distinctHouseholds);
        return new CommunityPromo(
                productId,
                firstRow.getProduct().getNormalizedName(),
                marketCnpj,
                PriceIndexService.cnpjRoot(marketCnpj),
                firstRow.getMarketName(),
                firstRow.getMarketName(),
                priceDrop.recentMedian(),
                priceDrop.baselineMedian(),
                priceDrop.dropPct(),
                priceDrop.recentSampleCount(),
                distinctHouseholds,
                geoEligibility.distanceKm(),
                isWatched
        );
    }

    /** K-anonymity/sample gate: distinct households when the group qualifies, null otherwise. */
    private Long distinctHouseholdsIfSampleGatePasses(UUID productId, String marketCnpj,
                                                      List<PriceObservation> rows, LocalDateTime since) {
        if (rows.size() < properties.getCollaborative().getMinObservationsForCommunityPromo()) return null;
        var distinctHouseholds = auditRepository.countDistinctHouseholdsForProductMarket(productId, marketCnpj, since);
        if (distinctHouseholds < properties.getCollaborative().getMinHouseholdsForPublic()) return null;
        return distinctHouseholds;
    }

    /** Geo-radius eligibility: distance (possibly null when not computable) when eligible, null record on rejection. */
    private GeoEligibility checkGeoEligibility(String marketCnpj, Map<String, MarketLocation> locations,
                                               boolean isWatched, BigDecimal userLatitude,
                                               BigDecimal userLongitude, Double radiusKm) {
        if (userLatitude == null || userLongitude == null) return new GeoEligibility(null);
        var location = locations.get(marketCnpj);
        if (location == null || !location.hasCoordinates()) {
            // user wants radius filter but no coords
            return (radiusKm != null && !isWatched) ? null : new GeoEligibility(null);
        }
        var distanceKm = DistanceCalculator.kmBetween(
                userLatitude, userLongitude, location.getLatitude(), location.getLongitude());
        if (radiusKm != null && distanceKm > radiusKm && !isWatched) return null;
        return new GeoEligibility(distanceKm);
    }

    /** Recent/baseline medians + drop percentage; null when the group has no qualifying price drop. */
    // False positive: median() returns null only for an empty list, and the guard
    // below (recentPrices non-empty, baselinePrices.size() >= 3) guarantees both
    // medians are non-null before they are dereferenced.
    @SuppressWarnings("java:S2259")
    private PriceDrop computePriceDrop(List<PriceObservation> rows, LocalDateTime recentCutoff) {
        // Use normalized R$/base-unit when ALL rows in the group carry it
        // (same product across time can shift pack sizes — a market that
        // moves milk from 1L to 2L bottles would otherwise look like a
        // huge price hike instead of a per-litre drop). Mixed → fall back
        // to raw unit price.
        var allNormalized = rows.stream().allMatch(observation -> observation.getNormalizedUnitPrice() != null);
        var priceFn = allNormalized
                ? (Function<PriceObservation, BigDecimal>) PriceObservation::getNormalizedUnitPrice
                : (Function<PriceObservation, BigDecimal>) PriceObservation::getUnitPrice;

        var recentPrices = rows.stream()
                .filter(observation -> observation.getObservedAt().isAfter(recentCutoff))
                .map(priceFn)
                .toList();
        // Baseline excludes prior promo-flagged rows: comparing a current
        // promo to an old promo would silence the signal we're trying
        // to detect. Promo rows still count toward 'recent'.
        var baselinePrices = rows.stream()
                .filter(observation -> !observation.getObservedAt().isAfter(recentCutoff))
                .filter(observation -> !observation.isPromoFlag())
                .map(priceFn)
                .toList();
        if (recentPrices.isEmpty() || baselinePrices.size() < 3) return null;

        var recentMedian = PriceIndexService.median(recentPrices);
        var baselineMedian = PriceIndexService.median(baselinePrices);
        var threshold = baselineMedian
                .multiply(BigDecimal.valueOf(100L - properties.getCollaborative().getCommunityPromoThresholdPct()))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        if (recentMedian.compareTo(threshold) >= 0) return null;

        var dropPct = baselineMedian.subtract(recentMedian)
                .multiply(BigDecimal.valueOf(100))
                .divide(baselineMedian, 2, RoundingMode.HALF_UP);
        return new PriceDrop(recentMedian, baselineMedian, dropPct, recentPrices.size());
    }

    private record GeoEligibility(Double distanceKm) {}

    private record PriceDrop(BigDecimal recentMedian, BigDecimal baselineMedian,
                             BigDecimal dropPct, int recentSampleCount) {}

    public record CommunityPromo(
            UUID productId,
            String productName,
            String marketCnpj,
            String marketCnpjRoot,
            String marketName,
            String marketFriendlyName,
            BigDecimal currentMedianPrice,
            BigDecimal baselineMedianPrice,
            BigDecimal dropPct,
            int recentSampleCount,
            long distinctHouseholds,
            Double distanceKm,
            boolean watching
    ) {
        /** Copy with the household's custom display name applied; leaves the original {@code marketName} intact. */
        public CommunityPromo withMarketFriendlyName(String marketFriendlyName) {
            return new CommunityPromo(productId, productName, marketCnpj, marketCnpjRoot, marketName,
                    marketFriendlyName, currentMedianPrice, baselineMedianPrice, dropPct,
                    recentSampleCount, distinctHouseholds, distanceKm, watching);
        }
    }
}
