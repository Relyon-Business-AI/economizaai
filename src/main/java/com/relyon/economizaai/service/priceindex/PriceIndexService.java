package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.PriceObservationAudit;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.MarketHouseholdCount;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.notifications.NotificationRuleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns the collaborative price index (Phase 4).
 *
 * <p>Write path:
 * <ul>
 *   <li>{@link #recordContributions(Receipt)} — called from
 *       ReceiptService.confirm(). Creates one anonymized PriceObservation
 *       per receipt item that has a linked product, plus a private audit row.
 *       Skipped entirely when the user has opted out of contribution
 *       OR the master switch is off.</li>
 * </ul>
 *
 * <p>Read paths (all gated by {@link CollaborativeProperties.Collaborative}
 * env-var thresholds — return empty when data is sparse):
 * <ul>
 *   <li>{@link #referencePrice} — median + min + sample size for a
 *       (product, market) pair. K-anonymity enforced.</li>
 *   <li>{@link #bestMarkets} — markets ranked by median price for a product.
 *       Each row independently k-anon checked.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceIndexService {

    private final PriceObservationRepository observationRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final CollaborativeProperties properties;
    private final MarketLocationService marketLocationService;
    private final MerchantSupportGate merchantSupportGate;
    private final NotificationRuleEngine notificationRuleEngine;

    @Transactional
    public int recordContributions(Receipt receipt) {
        if (isContributionBlocked(receipt)) {
            return 0;
        }

        var location = snapshotMarketLocation(receipt);
        var contributed = new ArrayList<PriceObservation>();
        for (var item : receipt.getItems()) {
            if (!isContributable(item)) continue;
            contributed.add(recordItemObservation(receipt, item, location));
        }
        log.info("price_index.write.done receipt={} contributed={} marketCnpj={}",
                receipt.getId(), contributed.size(), receipt.getCnpjEmitente());

        // Community retention loop: this household's prices may satisfy other
        // households' "avise-me quando" rules. Skips the contributor's own.
        notificationRuleEngine.evaluate(contributed, receipt.getHousehold().getId());
        return contributed.size();
    }

    /**
     * Skip guards for the whole write, in cheapest-first order: master switch,
     * user opt-out, missing market CNPJ, then the cross-household duplicate-chave
     * check (a DB hit, so last). Each logs its own reason before returning true.
     */
    private boolean isContributionBlocked(Receipt receipt) {
        if (!properties.getCollaborative().isEnabled()) {
            log.debug("price_index.write.skipped reason=master_switch_off receipt={}", receipt.getId());
            return true;
        }
        if (!receipt.getUser().isContributionOptIn()) {
            log.info("price_index.write.skipped reason=user_opt_out receipt={}", receipt.getId());
            return true;
        }
        if (receipt.getOrigin() == ReceiptOrigin.PHOTO) {
            // Vision-extracted receipts are unverifiable (no SEFAZ document) —
            // personal history only, never community price data.
            log.info("price_index.write.skipped reason=photo_origin receipt={}", receipt.getId());
            return true;
        }
        if (receipt.getCnpjEmitente() == null) {
            log.warn("price_index.write.skipped reason=no_market_cnpj receipt={}", receipt.getId());
            return true;
        }
        if (receipt.getChaveAcesso() != null
                && auditRepository.existsContributionForChaveFromOtherHousehold(
                        receipt.getChaveAcesso(), receipt.getHousehold().getId())) {
            log.warn("price_index.write.skipped reason=duplicate_chave_other_household receipt={} chave={}",
                    receipt.getId(), LogMasker.chave(receipt.getChaveAcesso()));
            return true;
        }
        var market = marketLocationService.findByCnpjs(List.of(receipt.getCnpjEmitente()))
                .get(receipt.getCnpjEmitente());
        if (!merchantSupportGate.contributesToIndex(market)) {
            // Grey-zone merchant: the receipt stays in the user's history, but its
            // prices wait for admin review before feeding the shared index.
            log.info("price_index.write.skipped reason=merchant_not_supported segment={} receipt={}",
                    market == null ? "UNREGISTERED" : market.getSegment(), receipt.getId());
            return true;
        }
        return false;
    }

    private boolean isContributable(ReceiptItem item) {
        return !item.isExcluded() && item.getProduct() != null && item.getUnitPrice() != null;
    }

    /**
     * Snapshot market city/state at write time so retroactive geocode changes
     * don't rewrite the history of where a price was observed.
     */
    private MarketSnapshot snapshotMarketLocation(Receipt receipt) {
        var marketLoc = marketLocationService.findByCnpjs(List.of(receipt.getCnpjEmitente()))
                .get(receipt.getCnpjEmitente());
        return new MarketSnapshot(
                marketLoc != null ? marketLoc.getCity() : null,
                marketLoc != null ? marketLoc.getState() : null,
                marketLoc != null ? marketLoc.getIbgeCityCode() : null);
    }

    /** Build + persist one anonymized observation and its private audit row. */
    private PriceObservation recordItemObservation(Receipt receipt, ReceiptItem item,
                                                   MarketSnapshot location) {
        var product = item.getProduct();
        var normalized = UnitConverter.normalizeItemPrice(
                item.getQuantity(), item.getUnit(),
                product.getPackSize(), product.getPackUnit(),
                item.getTotalPrice());
        var observation = PriceObservation.builder()
                .product(product)
                .marketCnpj(receipt.getCnpjEmitente())
                .marketCnpjRoot(cnpjRoot(receipt.getCnpjEmitente()))
                .marketName(receipt.getMarketName())
                .unitPrice(item.getUnitPrice())
                .quantity(item.getQuantity())
                .packSize(product.getPackSize())
                .packUnit(product.getPackUnit())
                .observedAt(receipt.getIssuedAt() != null ? receipt.getIssuedAt() : LocalDateTime.now())
                .promoFlag(item.isNfcePromoFlag())
                .normalizedUnitPrice(normalized.map(UnitConverter.NormalizedPrice::pricePerBaseUnit).orElse(null))
                .normalizedUnit(normalized.map(n -> n.baseUnit().name()).orElse(null))
                .city(location.city())
                .state(location.state())
                .ibgeCityCode(location.ibgeCityCode())
                .build();
        var saved = observationRepository.save(observation);

        auditRepository.save(PriceObservationAudit.builder()
                .observation(saved)
                .receiptId(receipt.getId())
                .householdId(receipt.getHousehold().getId())
                .contributedAt(LocalDateTime.now())
                .build());
        return saved;
    }

    private record MarketSnapshot(String city, String state, String ibgeCityCode) {}

    @Transactional(readOnly = true)
    public ReferencePrice referencePrice(UUID productId, String marketCnpj) {
        if (!properties.getCollaborative().isEnabled()) return ReferencePrice.empty();
        var since = LocalDateTime.now().minusDays(properties.getCollaborative().getLookbackDays());
        var observations = observationRepository.findRecentByProductAndMarket(productId, marketCnpj, since);
        var distinctHouseholds = observations.isEmpty()
                ? 0L
                : auditRepository.countDistinctHouseholdsForProductMarket(productId, marketCnpj, since);

        // Hybrid disclosure (PRO-54 AC2): when below k-anon or sample threshold,
        // surface counts so the FE can warn the user ("poucas amostras") without
        // ever leaking the actual sub-K-anon price. Price stays null in that case.
        if (observations.size() < properties.getCollaborative().getMinObservationsPerProductMarket()
                || distinctHouseholds < properties.getCollaborative().getMinHouseholdsForPublic()) {
            log.debug("reference_price.kanon_blocked product={} market={} samples={} households={}",
                    productId, marketCnpj, observations.size(), distinctHouseholds);
            return new ReferencePrice(null, null, null, observations.size(), distinctHouseholds, null, true);
        }
        var prices = observations.stream().map(PriceObservation::getUnitPrice).toList();
        return new ReferencePrice(median(prices), min(prices), max(prices),
                observations.size(), distinctHouseholds, observations.get(0).getObservedAt(), false);
    }

    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarkets(UUID productId, int limit,
                                            BigDecimal userLatitude, BigDecimal userLongitude, Double radiusKm,
                                            Set<String> watchedCnpjs) {
        if (!properties.getCollaborative().isEnabled()) return List.of();
        var since = LocalDateTime.now().minusDays(properties.getCollaborative().getLookbackDays());
        var observations = observationRepository.findRecentByProduct(productId, since);
        if (observations.isEmpty()) return List.of();

        var watched = watchedCnpjs == null ? Set.<String>of() : watchedCnpjs;
        var byMarket = observations.stream()
                .collect(Collectors.groupingBy(PriceObservation::getMarketCnpj));
        var locations = marketLocationService.findByCnpjs(new ArrayList<>(byMarket.keySet()));
        var householdCounts = auditRepository.countDistinctHouseholdsForProductByMarket(productId, since).stream()
                .collect(Collectors.toMap(MarketHouseholdCount::getCnpj, MarketHouseholdCount::getHouseholds));

        return byMarket.entrySet().stream()
                .map(entry -> toMarketRow(entry.getKey(), entry.getValue(),
                        householdCounts, locations, watched, userLatitude, userLongitude, radiusKm))
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(MarketPriceRow::watching, Comparator.reverseOrder())
                        .thenComparing(MarketPriceRow::medianPrice))
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * Build a public MarketPriceRow for one market's observations, or null if it
     * doesn't qualify: too few observations, fails k-anonymity, or (when a radius
     * is set) is out of range and not watched. Extracted from bestMarkets to keep
     * that method's cognitive complexity within bounds (S3776).
     */
    private MarketPriceRow toMarketRow(String cnpj, List<PriceObservation> rows,
                                       Map<String, Long> householdCounts,
                                       Map<String, MarketLocation> locations,
                                       Set<String> watched,
                                       BigDecimal userLatitude, BigDecimal userLongitude, Double radiusKm) {
        if (rows.size() < properties.getCollaborative().getMinObservationsPerProductMarket()) return null;
        var distinct = householdCounts.getOrDefault(cnpj, 0L);
        if (distinct < properties.getCollaborative().getMinHouseholdsForPublic()) return null;
        var location = locations.get(cnpj);
        var isWatched = watched.contains(cnpj);
        Double distanceKm = null;
        if (userLatitude != null && userLongitude != null && location != null && location.hasCoordinates()) {
            distanceKm = DistanceCalculator.kmBetween(
                    userLatitude, userLongitude, location.getLatitude(), location.getLongitude());
            if (radiusKm != null && distanceKm > radiusKm && !isWatched) return null;
        } else if (radiusKm != null && userLatitude != null && !isWatched) {
            return null;
        }
        var prices = rows.stream().map(PriceObservation::getUnitPrice).toList();
        return new MarketPriceRow(cnpj, cnpjRoot(cnpj), rows.get(0).getMarketName(),
                median(prices), min(prices), rows.size(), distinct, distanceKm, isWatched);
    }

    /** Median (50th percentile) of a price list. Returns null on empty. */
    static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        var sorted = values.stream().sorted().toList();
        var n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    static BigDecimal min(List<BigDecimal> values) {
        return values.stream().min(BigDecimal::compareTo).orElse(null);
    }

    static BigDecimal max(List<BigDecimal> values) {
        return values.stream().max(BigDecimal::compareTo).orElse(null);
    }

    public static String cnpjRoot(String cnpj) {
        if (cnpj == null || cnpj.length() < 8) return cnpj;
        return cnpj.substring(0, 8);
    }

    public record ReferencePrice(BigDecimal medianPrice, BigDecimal minPrice, BigDecimal maxPrice,
                                 int sampleCount, long distinctHouseholds, LocalDateTime mostRecentAt,
                                 boolean kAnonBlocked) {
        public static ReferencePrice empty() {
            return new ReferencePrice(null, null, null, 0, 0, null, false);
        }
        public boolean hasData() { return medianPrice != null; }
    }

    public record MarketPriceRow(String cnpj, String cnpjRoot, String marketName,
                                 BigDecimal medianPrice, BigDecimal minPrice,
                                 int sampleCount, long distinctHouseholds,
                                 Double distanceKm, boolean watching) {}
}
