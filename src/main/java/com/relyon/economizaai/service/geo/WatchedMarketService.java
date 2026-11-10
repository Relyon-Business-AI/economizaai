package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.MarketResponse;
import com.relyon.economizaai.exception.MarketNotFoundException;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.UserWatchedMarket;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserWatchedMarketRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the user's "watched markets" list — pinned CNPJs that should
 * surface in price intelligence regardless of distance from home.
 *
 * <p>Markets get into the system organically: every time a household
 * confirms a receipt, {@link MarketLocationService#registerMarketFromReceipt}
 * inserts the CNPJ into {@code market_locations}. Users then pick from
 * that list (filtered to "visited" or "nearby") via the FE.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchedMarketService {

    private final UserWatchedMarketRepository watchedRepository;
    private final MarketLocationRepository marketRepository;
    private final ReceiptRepository receiptRepository;
    private final MarketNameService marketNameService;
    private final SubscriptionGateService subscriptionGate;
    private final ViewerLocationService viewerLocationService;
    private final CollaborativeProperties properties;

    /** Scope da listagem de mercados: ALL = raio ∪ seguidos; MINE = só os seguidos. */
    public enum MarketScope { ALL, MINE }

    /** CNPJs the user is watching. Cheap helper for query-layer joins. */
    @Transactional(readOnly = true)
    public Set<String> watchedCnpjs(User user) {
        return watchedRepository.findAllByUserId(user.getId()).stream()
                .map(UserWatchedMarket::getMarketCnpj)
                .collect(Collectors.toSet());
    }

    /**
     * Just the user's own watchlist, hydrated with location data when we
     * have it. Used by the FE for the "Meus mercados" screen — separate
     * from the picker so we don't pay the picker's joins on every fetch.
     * Distance is from the viewer's resolved origin (device → profile →
     * notas → UF), so a user who never set a home still gets a real distance.
     */
    @Transactional(readOnly = true)
    public List<MarketResponse> listWatched(User user) {
        return listMarkets(user, MarketScope.MINE, null, null);
    }

    /**
     * Markets for the "Mercados" screen. {@code scope=ALL} returns every market within
     * {@code bestMarketsNearbyRadiusKm} (20 km) of the viewer's resolved origin UNION the
     * household's watched markets; {@code scope=MINE} returns only the watched ones.
     *
     * <p>Rows are sorted by distance ascending, nulls last — so watched-but-far markets
     * (a receipt scanned 3 000 km away on a trip) sink to the end instead of flooding the
     * top. The origin follows the "perto de você" cascade, with an optional real-time device
     * fix ({@code deviceLatitude}/{@code deviceLongitude}, all-or-nothing) winning when present.
     */
    @Transactional(readOnly = true)
    public List<MarketResponse> listMarkets(User user, MarketScope scope,
                                            BigDecimal deviceLatitude, BigDecimal deviceLongitude) {
        var householdId = user.getHousehold().getId();
        var visitedCnpjs = new HashSet<>(receiptRepository.findDistinctCnpjsByHousehold(householdId));
        var watchedCnpjs = watchedCnpjs(user);
        var origin = viewerLocationService.resolve(user, deviceLatitude, deviceLongitude);

        var locationsByCnpj = marketsByCnpj(scope, origin, watchedCnpjs);
        // MINE lists EVERY pinned market, even one we haven't geocoded yet (shown unhydrated,
        // distance null → sorted last). ALL lists only markets we have a location for.
        var cnpjsToShow = scope == MarketScope.MINE
                ? new HashSet<>(watchedCnpjs)
                : new HashSet<>(locationsByCnpj.keySet());
        cnpjsToShow.addAll(locationsByCnpj.keySet());

        var rows = cnpjsToShow.stream()
                .map(cnpj -> hydrate(cnpj, locationsByCnpj.get(cnpj), origin, visitedCnpjs, watchedCnpjs))
                .sorted(Comparator
                        .comparing(MarketResponse::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(row -> row.name() == null ? "" : row.name()))
                .toList();
        var named = applyFriendlyNames(householdId, rows);
        log.debug("markets.list household={} scope={} source={} rows={}",
                householdId, scope, origin.source(), named.size());
        return named;
    }

    /**
     * The geocoded {@link MarketLocation}s to consider, keyed by CNPJ. For MINE: the watched
     * markets we have a location for. For ALL: markets within the nearby radius of the origin
     * UNION the watched markets (watched always included regardless of distance — they may be
     * far on purpose).
     */
    private Map<String, MarketLocation> marketsByCnpj(MarketScope scope,
                                                      ViewerLocationService.ViewerLocation origin,
                                                      Set<String> watchedCnpjs) {
        var watched = watchedCnpjs.isEmpty()
                ? List.<MarketLocation>of()
                : marketRepository.findAllByCnpjIn(List.copyOf(watchedCnpjs));
        if (scope == MarketScope.MINE) {
            return byCnpj(watched.stream());
        }
        var radiusKm = properties.getCollaborative().getBestMarketsNearbyRadiusKm();
        var nearby = origin.hasCoordinates()
                ? marketRepository.findAll().stream()
                    .filter(MarketLocation::hasCoordinates)
                    .filter(market -> DistanceCalculator.kmBetween(
                            origin.latitude(), origin.longitude(),
                            market.getLatitude(), market.getLongitude()) <= radiusKm)
                : Stream.<MarketLocation>empty();
        return byCnpj(Stream.concat(nearby, watched.stream()));
    }

    private Map<String, MarketLocation> byCnpj(Stream<MarketLocation> markets) {
        return markets.collect(Collectors.toMap(
                MarketLocation::getCnpj, market -> market, (first, ignored) -> first));
    }

    private MarketResponse hydrate(String cnpj, MarketLocation market,
                                   ViewerLocationService.ViewerLocation origin,
                                   Set<String> visitedCnpjs, Set<String> watchedCnpjs) {
        if (market == null) {
            return new MarketResponse(cnpj, null, null, null, null, null, null, null, null, null,
                    visitedCnpjs.contains(cnpj), watchedCnpjs.contains(cnpj), null, null);
        }
        return MarketResponse.from(market, distanceFromOrigin(origin, market),
                visitedCnpjs.contains(cnpj), watchedCnpjs.contains(cnpj));
    }

    private Double distanceFromOrigin(ViewerLocationService.ViewerLocation origin, MarketLocation market) {
        if (!origin.hasCoordinates() || !market.hasCoordinates()) return null;
        return DistanceCalculator.kmBetween(
                origin.latitude(), origin.longitude(), market.getLatitude(), market.getLongitude());
    }

    @Transactional
    public MarketResponse watch(User user, String cnpj) {
        var normalized = CnpjNormalizer.normalize(cnpj);
        var location = marketRepository.findByCnpj(normalized).orElseThrow(() -> new MarketNotFoundException(normalized));
        var existing = watchedRepository.findByUserIdAndMarketCnpj(user.getId(), normalized);
        if (existing.isEmpty()) {
            // Cap only NEW pins — re-pinning an existing one is always allowed.
            var current = watchedRepository.findAllByUserId(user.getId()).size();
            if (current >= subscriptionGate.watchedMarketLimit(user)) {
                throw new PaywallException(Feature.WATCHED_MARKETS_UNLIMITED.name());
            }
            watchedRepository.save(UserWatchedMarket.builder()
                    .user(user)
                    .marketCnpj(normalized)
                    .build());
            log.info("watched_market.added user={} cnpj={}", LogMasker.email(user.getEmail()), normalized);
        }
        var visited = receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()).contains(normalized);
        var response = MarketResponse.from(location, distanceFromHome(user, location), visited, true);
        return response.withFriendlyName(marketNameService.resolve(user.getHousehold().getId(), normalized, response.name()));
    }

    /**
     * Set each market's {@code friendlyName} to the household's custom rename when present
     * (the original {@code name} is left intact). Batched to avoid N+1.
     */
    private List<MarketResponse> applyFriendlyNames(UUID householdId, List<MarketResponse> rows) {
        if (rows.isEmpty()) return rows;
        var overrides = marketNameService.resolveNames(householdId,
                rows.stream().map(MarketResponse::cnpj).toList());
        if (overrides.isEmpty()) return rows;
        return rows.stream()
                .map(row -> row.withFriendlyName(marketNameService.applyOverride(overrides, row.cnpj(), row.friendlyName())))
                .toList();
    }

    @Transactional
    public void unwatch(User user, String cnpj) {
        var normalized = CnpjNormalizer.normalize(cnpj);
        watchedRepository.deleteByUserIdAndMarketCnpj(user.getId(), normalized);
        log.info("watched_market.removed user={} cnpj={}", LogMasker.email(user.getEmail()), normalized);
    }

    private Double distanceFromHome(User user, MarketLocation location) {
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null
                || !location.hasCoordinates()) return null;
        return DistanceCalculator.kmBetween(
                user.getHomeLatitude(), user.getHomeLongitude(),
                location.getLatitude(), location.getLongitude());
    }
}
