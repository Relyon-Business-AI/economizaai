package com.relyon.economizaai.service.geo;

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
     */
    @Transactional(readOnly = true)
    public List<MarketResponse> listWatched(User user) {
        var pinned = watchedRepository.findAllByUserId(user.getId());
        if (pinned.isEmpty()) return List.of();

        var cnpjs = pinned.stream().map(UserWatchedMarket::getMarketCnpj).toList();
        var locations = marketRepository.findAllByCnpjIn(cnpjs).stream()
                .collect(Collectors.toMap(MarketLocation::getCnpj, m -> m));
        var visitedCnpjs = new HashSet<>(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()));

        var rows = pinned.stream()
                .map(p -> {
                    var loc = locations.get(p.getMarketCnpj());
                    if (loc == null) {
                        return new MarketResponse(p.getMarketCnpj(), null, null, null, null, null, null, null,
                                visitedCnpjs.contains(p.getMarketCnpj()), true);
                    }
                    return MarketResponse.from(loc, distanceFromHome(user, loc),
                            visitedCnpjs.contains(p.getMarketCnpj()), true);
                })
                .sorted(Comparator
                        .comparing((MarketResponse r) -> r.distanceKm() == null ? Double.MAX_VALUE : r.distanceKm())
                        .thenComparing(r -> r.name() == null ? "" : r.name()))
                .toList();
        return applyFriendlyNames(user.getHousehold().getId(), rows);
    }

    /**
     * Catalogue markets for the picker. Combines:
     * <ul>
     *   <li>Markets the household has shopped at (best signal).</li>
     *   <li>Markets the user already watches.</li>
     *   <li>If radiusKm + home set: nearby markets we already have geocoded.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<MarketResponse> listForPicker(User user, Double radiusKm) {
        var visitedCnpjs = new HashSet<>(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()));
        var watchedCnpjs = watchedCnpjs(user);

        var unionCnpjs = new HashSet<String>();
        unionCnpjs.addAll(visitedCnpjs);
        unionCnpjs.addAll(watchedCnpjs);
        var visitedAndWatched = unionCnpjs.isEmpty()
                ? List.<MarketLocation>of()
                : marketRepository.findAllByCnpjIn(List.copyOf(unionCnpjs));

        var nearby = (radiusKm != null && user.getHomeLatitude() != null && user.getHomeLongitude() != null)
                ? marketRepository.findAll().stream()
                        .filter(MarketLocation::hasCoordinates)
                        .filter(m -> !unionCnpjs.contains(m.getCnpj()))
                        .filter(m -> DistanceCalculator.kmBetween(
                                user.getHomeLatitude(), user.getHomeLongitude(),
                                m.getLatitude(), m.getLongitude()) <= radiusKm)
                        .toList()
                : List.<MarketLocation>of();

        var rows = Stream.concat(visitedAndWatched.stream(), nearby.stream())
                .map(m -> MarketResponse.from(
                        m,
                        distanceFromHome(user, m),
                        visitedCnpjs.contains(m.getCnpj()),
                        watchedCnpjs.contains(m.getCnpj())))
                .sorted(Comparator
                        .comparing(MarketResponse::watching).reversed()
                        .thenComparing(MarketResponse::visited, Comparator.reverseOrder())
                        .thenComparing(r -> r.distanceKm() == null ? Double.MAX_VALUE : r.distanceKm()))
                .toList();
        return applyFriendlyNames(user.getHousehold().getId(), rows);
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
