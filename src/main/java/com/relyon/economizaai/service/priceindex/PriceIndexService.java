package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.PriceObservationAudit;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ChainHouseholdCount;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.geo.NearbyScope;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.notifications.NotificationRuleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
 *   <li>{@link #bestMarkets} — market CHAINS ranked by median price for a product
 *       (one row per CNPJ root, showing its closest/watched store). Each row
 *       independently k-anon checked at chain level.</li>
 *   <li>{@link #inStoreReferencePrice} — "typical in-store price" across every
 *       market, scoped to the household's states and falling back to national.
 *       The fallback card for when no chain reaches K yet.</li>
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
    private final ReceiptRepository receiptRepository;
    private final PrivacyTierResolver privacyTierResolver;

    @Transactional
    public int recordContributions(Receipt receipt) {
        if (isContributionBlocked(receipt)) {
            return 0;
        }

        var channel = receipt.getChannel();
        var location = snapshotMarketLocation(receipt);
        var contributed = new ArrayList<PriceObservation>();
        for (var item : receipt.getItems()) {
            if (!isContributable(item, channel)) continue;
            contributed.add(recordItemObservation(receipt, item, location));
        }
        log.info("price_index.write.done receipt={} channel={} contributed={} marketCnpj={}",
                receipt.getId(), channel, contributed.size(), receipt.getCnpjEmitente());

        // Community retention loop: this household's prices may satisfy other households'
        // "avise-me quando" rules. Those rules are location-based (physical), so only feed
        // IN_STORE observations — an online national price shouldn't fire a local-market watch.
        var physicalContributions = contributed.stream()
                .filter(observation -> observation.getChannel() == ReceiptChannel.IN_STORE)
                .toList();
        notificationRuleEngine.evaluate(physicalContributions, receipt.getHousehold().getId());
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
        if (!receipt.isIndexEligible()) {
            // On-device fetch from a non-trusted state: client-authored content whose only
            // tie to SEFAZ is the embedded chave (forgeable), so personal history only —
            // same trust class as PHOTO. Server-fetched and trusted device-fetch are eligible.
            log.info("price_index.write.skipped reason=not_index_eligible receipt={}", receipt.getId());
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
        // The merchant-segment gate only applies to the PHYSICAL index: a physical
        // supermarket means "everything here is groceries". Online, the merchant CNAE is a
        // poor proxy (a marketplace sells a real grocery EAN next to books), so the online
        // index gates per-item by EAN instead — see isContributable.
        if (receipt.getChannel() == ReceiptChannel.IN_STORE) {
            var market = marketLocationService.findByCnpjs(List.of(receipt.getCnpjEmitente()))
                    .get(receipt.getCnpjEmitente());
            if (!merchantSupportGate.contributesToIndex(market)) {
                // Grey-zone merchant: the receipt stays in the user's history, but its
                // prices wait for admin review before feeding the shared index.
                log.info("price_index.write.skipped reason=merchant_not_supported segment={} receipt={}",
                        market == null ? "UNREGISTERED" : market.getSegment(), receipt.getId());
                return true;
            }
        }
        return false;
    }

    /**
     * Physical: any linked, non-excluded, priced item contributes (the merchant gate already
     * vouched for the store). Online: additionally require a real EAN — the item's identity
     * comes from its GTIN (matched to a canonical product), not from the seller's segment.
     */
    private boolean isContributable(ReceiptItem item, ReceiptChannel channel) {
        if (item.isExcluded() || item.getProduct() == null || item.getUnitPrice() == null) {
            return false;
        }
        if (channel == ReceiptChannel.ONLINE) {
            return item.getEan() != null && !item.getEan().isBlank();
        }
        return true;
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
                .channel(receipt.getChannel())
                .marketCnpj(receipt.getCnpjEmitente())
                .marketCnpjRoot(cnpjRoot(receipt.getCnpjEmitente()))
                .marketName(receipt.getMarketName())
                .unitPrice(item.getUnitPrice())
                .quantity(item.getQuantity())
                .packSize(product.getPackSize())
                .packUnit(product.getPackUnit())
                // DAY precision on purpose: a minute-exact timestamp would let a reader re-group
                // the observations of one store+moment back into a single household's receipt.
                .observedAt(observationDay(receipt.getIssuedAt()))
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

    static LocalDateTime observationDay(LocalDateTime issuedAt) {
        var moment = issuedAt != null ? issuedAt : BrazilClock.nowDateTime();
        return moment.toLocalDate().atStartOfDay();
    }

    @Transactional(readOnly = true)
    public ReferencePrice referencePrice(UUID productId, String marketCnpj) {
        if (!properties.getCollaborative().isEnabled()) return ReferencePrice.empty();
        var since = BrazilClock.nowDateTime().minusDays(properties.getCollaborative().getLookbackDays());
        var observations = observationRepository.findRecentByProductAndMarket(productId, marketCnpj, since);
        return aggregateReference("reference_price", productId, observations,
                () -> auditRepository.countDistinctHouseholdsForProductMarket(productId, marketCnpj, since),
                thresholdsFor(productId, observations));
    }

    /**
     * Thresholds for an aggregate over these observations: the full K when the product is
     * sensitive (health) or any of the sellers is a pharmacy, the relaxed grocery tier otherwise.
     */
    private PrivacyTierResolver.Thresholds thresholdsFor(UUID productId, List<PriceObservation> observations) {
        if (observations.isEmpty()) return privacyTierResolver.thresholdsFor(true);
        var sensitive = privacyTierResolver.isSensitiveProduct(productId) || anySensitiveStore(observations);
        return privacyTierResolver.thresholdsFor(sensitive);
    }

    /** A store with no location row counts as sensitive (unknown segment), so look each CNPJ up. */
    private boolean anySensitiveStore(List<PriceObservation> observations) {
        var cnpjs = observations.stream().map(PriceObservation::getMarketCnpj).distinct().toList();
        var locations = marketLocationService.findByCnpjs(new ArrayList<>(cnpjs));
        return cnpjs.stream().map(locations::get).anyMatch(PrivacyTierResolver::isSensitiveMarket);
    }

    /**
     * National ONLINE reference price for a product — median across all online sellers
     * (marketplaces + supermarket delivery sites), no geo. A separate series from the
     * physical index; same k-anon + min-sample thresholds. Never mixes with in-store prices.
     */
    @Transactional(readOnly = true)
    public ReferencePrice onlineReferencePrice(UUID productId) {
        if (!properties.getCollaborative().isEnabled()) return ReferencePrice.empty();
        var since = BrazilClock.nowDateTime().minusDays(properties.getCollaborative().getLookbackDays());
        var observations = observationRepository.findRecentOnlineByProduct(productId, since);
        return aggregateReference("online_reference_price", productId, observations,
                () -> auditRepository.countDistinctOnlineHouseholdsForProduct(productId, since),
                thresholdsFor(productId, observations));
    }

    /**
     * "Typical in-store price" for a product across EVERY physical market — the fallback
     * for the best-markets screen when no single chain reaches K yet. Scoped first to the
     * states the household shops in (its confirmed receipts' UFs); when that slice is below
     * K / min-sample, falls back to the national in-store series. Same k-anon rules as the
     * other references, applied across markets instead of per market.
     */
    @Transactional(readOnly = true)
    public InStoreReferencePrice inStoreReferencePrice(UUID productId, UUID householdId) {
        if (!properties.getCollaborative().isEnabled()) return InStoreReferencePrice.empty();
        var since = bestMarketsSince();
        var states = receiptRepository.findConfirmedUfsForHousehold(householdId).stream()
                .map(Enum::name)
                .toList();
        if (!states.isEmpty()) {
            var regionalObservations = observationRepository.findRecentByProductAndStates(productId, states, since);
            var regional = aggregateReference("in_store_reference_price.state", productId, regionalObservations,
                    () -> auditRepository.countDistinctInStoreHouseholdsForProductInStates(productId, states, since),
                    thresholdsFor(productId, regionalObservations));
            if (regional.hasData()) return new InStoreReferencePrice(ReferenceScope.STATE, states, regional);
        }
        var nationalObservations = observationRepository.findRecentByProduct(productId, since);
        var national = aggregateReference("in_store_reference_price.national", productId, nationalObservations,
                () -> auditRepository.countDistinctInStoreHouseholdsForProduct(productId, since),
                thresholdsFor(productId, nationalObservations));
        return new InStoreReferencePrice(ReferenceScope.NATIONAL, List.of(), national);
    }

    /**
     * Hybrid disclosure (PRO-54 AC2): when below k-anon or sample threshold, surface the
     * counts so the FE can warn the user ("poucas amostras") without ever leaking the
     * actual sub-K-anon price — price stays null in that case. The household count is a
     * supplier so the (DB) k-anon query only runs when there are observations to gate.
     */
    private ReferencePrice aggregateReference(String logTag, UUID productId,
                                              List<PriceObservation> observations,
                                              LongSupplier distinctHouseholdCounter,
                                              PrivacyTierResolver.Thresholds thresholds) {
        var distinctHouseholds = observations.isEmpty() ? 0L : distinctHouseholdCounter.getAsLong();
        if (thresholds.blocks(observations.size(), distinctHouseholds)) {
            log.debug("{}.kanon_blocked product={} samples={} households={} minHouseholds={}",
                    logTag, productId, observations.size(), distinctHouseholds, thresholds.minHouseholds());
            return new ReferencePrice(null, null, null, observations.size(), distinctHouseholds, null, true);
        }
        var prices = observations.stream().map(PriceObservation::getUnitPrice).toList();
        var mostRecent = observations.stream()
                .max(Comparator.comparing(PriceObservation::getObservedAt))
                .orElse(observations.get(0));
        return new ReferencePrice(medianForApi(prices), min(prices), max(prices),
                observations.size(), distinctHouseholds, mostRecent.getObservedAt(), false);
    }

    /**
     * Markets ranked by price for a product, ONE ROW PER CHAIN (CNPJ root). Stores of the
     * same chain practice the same shelf price, so pooling them keeps the answer ("where is
     * it cheapest") intact while reaching k-anonymity far sooner than per store. Each row
     * surfaces one concrete store of the chain — a watched one if any, else the closest to
     * the viewer, else the most recently seen — so the FE keeps a real CNPJ to watch/route to.
     */
    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarkets(UUID productId, int limit,
                                            BigDecimal userLatitude, BigDecimal userLongitude, Double radiusKm,
                                            Set<String> watchedCnpjs) {
        return bestMarkets(productId, limit, userLatitude, userLongitude, radiusKm, watchedCnpjs,
                ComparisonSort.CHEAPEST);
    }

    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarkets(UUID productId, int limit,
                                            BigDecimal userLatitude, BigDecimal userLongitude, Double radiusKm,
                                            Set<String> watchedCnpjs, ComparisonSort sort) {
        return rankChains(productId, limit, new Viewer(userLatitude, userLongitude, radiusKm,
                watchedCnpjs == null ? Set.of() : watchedCnpjs), observation -> true, sort);
    }

    /**
     * Best-markets scoped to the viewer — the same "perto de você" cascade as the promos feed,
     * applied server-side so a shopper in Porto Alegre doesn't get a chain 400 km away:
     * <ol>
     *   <li>has a location (explicit GPS/CEP or inferred from their receipts) → chains with a
     *       store within {@code bestMarketsNearbyRadiusKm} or a watched store. No widening to
     *       the whole state: "nothing nearby" is an honest answer (the in-store typical price
     *       card covers it).</li>
     *   <li>no location but watches markets → only the watched chains;</li>
     *   <li>else the viewer's majority state → chains with a store there;</li>
     *   <li>else national.</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarketsForViewer(UUID productId, int limit, ViewerLocation location,
                                                     Set<String> watchedCnpjs) {
        return bestMarketsForViewer(productId, limit, location, watchedCnpjs, ComparisonSort.CHEAPEST);
    }

    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarketsForViewer(UUID productId, int limit, ViewerLocation location,
                                                     Set<String> watchedCnpjs, ComparisonSort sort) {
        return bestMarketsInScope(productId, limit, new NearbyScope(location,
                watchedCnpjs == null ? Set.of() : watchedCnpjs,
                properties.getCollaborative().getBestMarketsNearbyRadiusKm()), sort);
    }

    /** {@link #bestMarketsForViewer} with a caller-built scope (custom radius). */
    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarketsInScope(UUID productId, int limit, NearbyScope scope) {
        return bestMarketsInScope(productId, limit, scope, ComparisonSort.CHEAPEST);
    }

    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarketsInScope(UUID productId, int limit, NearbyScope scope, ComparisonSort sort) {
        var location = scope.location();
        var watched = scope.watchedCnpjs();
        if (location.hasCoordinates()) {
            var viewer = new Viewer(location.latitude(), location.longitude(), scope.radiusKm(), watched);
            log.debug("best_markets.scope product={} scope=NEARBY source={} radiusKm={}", productId, location.source(), scope.radiusKm());
            return rankChains(productId, limit, viewer, observation -> true, sort);
        }
        var unlocated = new Viewer(null, null, null, watched);
        if (!watched.isEmpty()) {
            var watchedRows = rankChains(productId, limit, unlocated, observation -> watched.contains(observation.getMarketCnpj()), sort);
            if (!watchedRows.isEmpty()) {
                log.debug("best_markets.scope product={} scope=WATCHED", productId);
                return watchedRows;
            }
        }
        if (location.uf() != null) {
            log.debug("best_markets.scope product={} scope=STATE uf={}", productId, location.uf());
            return rankChains(productId, limit, unlocated, observation -> location.uf().equals(observation.getState()), sort);
        }
        log.debug("best_markets.scope product={} scope=NATIONAL", productId);
        return rankChains(productId, limit, unlocated, observation -> true, sort);
    }

    /**
     * Lowest recent in-store price per product, over the same observation window the best-markets
     * ranking uses. Products without any recent observation are absent from the map.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> lowestRecentInStorePrices(Collection<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return observationRepository.findLowestRecentInStorePrices(productIds, bestMarketsSince()).stream()
                .collect(Collectors.toMap(PriceObservationRepository.ProductLowestPrice::getProductId,
                        PriceObservationRepository.ProductLowestPrice::getLowestPrice));
    }

    /** Only the chains the viewer watches ("Mercados que sigo"); empty when they watch none. */
    @Transactional(readOnly = true)
    public List<MarketPriceRow> bestMarketsAtWatched(UUID productId, int limit, Set<String> watchedCnpjs) {
        if (watchedCnpjs == null || watchedCnpjs.isEmpty()) return List.of();
        return rankChains(productId, limit, new Viewer(null, null, null, watchedCnpjs),
                observation -> watchedCnpjs.contains(observation.getMarketCnpj()), ComparisonSort.CHEAPEST);
    }

    /**
     * {@code storeInScope} decides WHICH chains appear (a chain needs ≥1 in-scope store) and
     * which of its stores represents it. The chain's PRICE still aggregates all its
     * observations: the k-anon household count is chain-wide, so pricing a filtered subset
     * would disclose fewer households than the gate checked.
     */
    private List<MarketPriceRow> rankChains(UUID productId, int limit, Viewer viewer,
                                            Predicate<PriceObservation> storeInScope, ComparisonSort sort) {
        if (!properties.getCollaborative().isEnabled()) return List.of();
        var since = bestMarketsSince();
        var observations = observationRepository.findRecentByProduct(productId, since);
        if (observations.isEmpty()) return List.of();

        var byChain = observations.stream()
                .collect(Collectors.groupingBy(PriceIndexService::chainKey));
        var storeCnpjs = observations.stream().map(PriceObservation::getMarketCnpj).distinct().toList();
        var locations = marketLocationService.findByCnpjs(new ArrayList<>(storeCnpjs));
        var householdsByChain = auditRepository.countDistinctHouseholdsForProductByChain(productId, since).stream()
                .collect(Collectors.toMap(ChainHouseholdCount::getCnpjRoot, ChainHouseholdCount::getHouseholds));
        var sensitiveProduct = privacyTierResolver.isSensitiveProduct(productId);

        var qualifying = byChain.entrySet().stream()
                .map(entry -> toChainRow(entry.getKey(), entry.getValue(),
                        householdsByChain.getOrDefault(entry.getKey(), 0L), locations, viewer,
                        chainThresholds(sensitiveProduct, entry.getValue(), locations), storeInScope))
                .filter(Objects::nonNull)
                .toList();
        // Badges persistentes decididos sobre TODAS as linhas que passaram (antes do limite),
        // depois ordena por sort e corta — assim "Mais barato"/"Mais perto" não dependem do corte.
        return markAndSortChains(qualifying, sort).stream()
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * Marca os badges (menor preço = menor {@code medianPrice}; mais perto = menor
     * {@code distanceKm} não-nulo) sobre a lista inteira e ordena: CHEAPEST mantém o
     * comportamento histórico (seguidos primeiro, depois preço asc); NEAREST por distância
     * asc (nulos por último). Os badges independem da ordenação.
     */
    private List<MarketPriceRow> markAndSortChains(List<MarketPriceRow> rows, ComparisonSort sort) {
        var cheapest = rows.stream()
                .filter(row -> row.medianPrice() != null)
                .min(Comparator.comparing(MarketPriceRow::medianPrice))
                .orElse(null);
        var nearest = rows.stream()
                .filter(row -> row.distanceKm() != null)
                .min(Comparator.comparing(MarketPriceRow::distanceKm))
                .orElse(null);
        return rows.stream()
                .map(row -> row.withBadges(row == cheapest, row == nearest))
                .sorted(chainComparatorFor(sort))
                .toList();
    }

    private static Comparator<MarketPriceRow> chainComparatorFor(ComparisonSort sort) {
        if (sort == ComparisonSort.NEAREST) {
            return Comparator
                    .comparing(MarketPriceRow::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(MarketPriceRow::medianPrice, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        return Comparator
                .comparing(MarketPriceRow::watching, Comparator.reverseOrder())
                .thenComparing(MarketPriceRow::medianPrice, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private LocalDateTime bestMarketsSince() {
        return BrazilClock.nowDateTime().minusDays(properties.getCollaborative().getBestMarketsLookbackDays());
    }

    private static String chainKey(PriceObservation observation) {
        return observation.getMarketCnpjRoot() != null
                ? observation.getMarketCnpjRoot()
                : cnpjRoot(observation.getMarketCnpj());
    }

    /**
     * Build the public row for one chain's observations, or null if it doesn't qualify:
     * too few observations, fails k-anonymity, or (when a radius is set) none of its
     * stores is in range or watched.
     */
    private PrivacyTierResolver.Thresholds chainThresholds(boolean sensitiveProduct, List<PriceObservation> rows,
                                                           Map<String, MarketLocation> locations) {
        var pharmacyInChain = rows.stream()
                .map(observation -> locations.get(observation.getMarketCnpj()))
                .anyMatch(PrivacyTierResolver::isSensitiveMarket);
        return privacyTierResolver.thresholdsFor(sensitiveProduct || pharmacyInChain);
    }

    private MarketPriceRow toChainRow(String cnpjRoot, List<PriceObservation> rows, long distinctHouseholds,
                                      Map<String, MarketLocation> locations, Viewer viewer,
                                      PrivacyTierResolver.Thresholds thresholds,
                                      Predicate<PriceObservation> storeInScope) {
        if (thresholds.blocks(rows.size(), distinctHouseholds)) return null;
        var inScopeRows = rows.stream().filter(storeInScope).toList();
        if (inScopeRows.isEmpty()) return null;
        var displayStore = pickDisplayStore(inScopeRows, locations, viewer);
        if (displayStore == null) return null;
        var prices = rows.stream().map(PriceObservation::getUnitPrice).toList();
        // The "onde está mais barato" screen shows a REAL observed price + its date, not the
        // median — a shopper needs the actual number they'll (roughly) see, and the date lets
        // them judge staleness. k-anon still holds via the ≥K-households gate above; the median
        // stays as the "usual price" baseline (deals). Most recent across the chain.
        var mostRecent = mostRecent(rows);
        return new MarketPriceRow(displayStore.cnpj(), cnpjRoot, displayStore.name(),
                medianForApi(prices), min(prices), mostRecent.getUnitPrice(), mostRecent.getObservedAt(),
                rows.size(), distinctHouseholds, displayStore.distanceKm(), displayStore.watching(),
                false, false);
    }

    /**
     * The concrete store a chain row is shown as: watched first, then closest to the viewer,
     * then most recently seen. Null when a radius is set, the viewer has coordinates, and
     * the best candidate is neither watched nor within range (so no store of the chain is).
     */
    private DisplayStore pickDisplayStore(List<PriceObservation> rows,
                                          Map<String, MarketLocation> locations, Viewer viewer) {
        var candidates = rows.stream()
                .collect(Collectors.groupingBy(PriceObservation::getMarketCnpj))
                .entrySet().stream()
                .map(store -> toDisplayStore(store.getKey(), store.getValue(), locations.get(store.getKey()), viewer))
                .sorted(Comparator
                        .comparing(DisplayStore::watching, Comparator.reverseOrder())
                        .thenComparing(DisplayStore::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DisplayStore::lastSeenAt, Comparator.reverseOrder()))
                .toList();
        var best = candidates.get(0);
        var radiusApplies = viewer.radiusKm() != null && viewer.hasCoordinates();
        if (radiusApplies && !best.watching()
                && (best.distanceKm() == null || best.distanceKm() > viewer.radiusKm())) {
            return null;
        }
        return best;
    }

    private DisplayStore toDisplayStore(String cnpj, List<PriceObservation> storeRows,
                                        MarketLocation location, Viewer viewer) {
        Double distanceKm = null;
        if (viewer.hasCoordinates() && location != null && location.hasCoordinates()) {
            distanceKm = DistanceCalculator.kmBetween(
                    viewer.latitude(), viewer.longitude(), location.getLatitude(), location.getLongitude());
        }
        var lastSeen = mostRecent(storeRows);
        return new DisplayStore(cnpj, lastSeen.getMarketName(), lastSeen.getObservedAt(),
                distanceKm, viewer.watched().contains(cnpj));
    }

    private static PriceObservation mostRecent(List<PriceObservation> rows) {
        return rows.stream().max(Comparator.comparing(PriceObservation::getObservedAt)).orElse(rows.get(0));
    }

    private record Viewer(BigDecimal latitude, BigDecimal longitude, Double radiusKm, Set<String> watched) {
        boolean hasCoordinates() { return latitude != null && longitude != null; }
    }

    private record DisplayStore(String cnpj, String name, LocalDateTime lastSeenAt,
                                Double distanceKm, boolean watching) {}

    /** Median rounded to 2 decimals (R$) for API responses — mirror of DealsService. */
    private static BigDecimal medianForApi(List<BigDecimal> values) {
        var medianValue = median(values);
        return medianValue == null ? null : medianValue.setScale(2, RoundingMode.HALF_UP);
    }

    /** Median (50th percentile) of a price list. Returns null on empty. */
    public static BigDecimal median(List<BigDecimal> values) {
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

    /** One chain in the best-markets ranking; {@code cnpj}/{@code marketName}/{@code distanceKm}
     *  describe the concrete store the chain is shown as (watched → closest → most recent).
     *  {@code isCheapest}/{@code isNearest} are persistent badges decided over the whole list,
     *  independent of the active sort. */
    public record MarketPriceRow(String cnpj, String cnpjRoot, String marketName,
                                 BigDecimal medianPrice, BigDecimal minPrice,
                                 BigDecimal latestPrice, LocalDateTime latestObservedAt,
                                 int sampleCount, long distinctHouseholds,
                                 Double distanceKm, boolean watching,
                                 boolean isCheapest, boolean isNearest) {
        /** Copy com os badges persistentes definidos (decididos sobre a lista inteira). */
        public MarketPriceRow withBadges(boolean cheapest, boolean nearest) {
            return new MarketPriceRow(cnpj, cnpjRoot, marketName, medianPrice, minPrice, latestPrice,
                    latestObservedAt, sampleCount, distinctHouseholds, distanceKm, watching, cheapest, nearest);
        }
    }

    /** Which slice the in-store typical price was computed over. */
    public enum ReferenceScope { STATE, NATIONAL }

    /** In-store typical price + the slice it came from ({@code states} is empty when NATIONAL). */
    public record InStoreReferencePrice(ReferenceScope scope, List<String> states, ReferencePrice reference) {
        public static InStoreReferencePrice empty() {
            return new InStoreReferencePrice(ReferenceScope.NATIONAL, List.of(), ReferencePrice.empty());
        }
    }
}
