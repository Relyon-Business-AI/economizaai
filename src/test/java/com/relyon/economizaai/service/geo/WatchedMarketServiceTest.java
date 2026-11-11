package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.geo.WatchedMarketService.MarketScope;
import com.relyon.economizaai.exception.InvalidCnpjException;
import com.relyon.economizaai.exception.MarketNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.UserWatchedMarket;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserWatchedMarketRepository;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchedMarketServiceTest {

    @Mock private UserWatchedMarketRepository watchedRepository;
    @Mock private MarketLocationRepository marketRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private MarketNameService marketNameService;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private ViewerLocationService viewerLocationService;

    private WatchedMarketService service;
    private User user;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        properties.getCollaborative().setBestMarketsNearbyRadiusKm(10);
        service = new WatchedMarketService(watchedRepository, marketRepository, receiptRepository,
                marketNameService, subscriptionGate, viewerLocationService, properties);
        // Default: PRO-equivalent unlimited cap so existing tests keep their intent.
        lenient().when(subscriptionGate.watchedMarketLimit(any())).thenReturn(Integer.MAX_VALUE);
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.resolveChainNames(any())).thenReturn(Map.of());
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        // Batch cascade: delegate to the real static cleanLegalName on the raw name (4th arg),
        // so the list endpoint is exercised end-to-end through the heuristic.
        lenient().when(marketNameService.friendlyName(any(Map.class), any(Map.class), any(), any()))
                .thenAnswer(invocation -> MarketNameService.cleanLegalName(invocation.getArgument(3)));
        lenient().when(marketNameService.friendlyName(any(UUID.class), any(), any()))
                .thenAnswer(invocation -> MarketNameService.cleanLegalName(invocation.getArgument(2)));
        // Default origin: unlocated (keeps NEARBY tests explicit about their own location).
        lenient().when(viewerLocationService.resolve(any(), any(), any()))
                .thenReturn(new ViewerLocation(null, null, Source.NONE, "RS"));
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void watch_throwsWhenMarketUnknown() {
        when(marketRepository.findByCnpj("99999999000111")).thenReturn(Optional.empty());

        assertThrows(MarketNotFoundException.class,
                () -> service.watch(user, "99999999000111"));
        verify(watchedRepository, never()).save(any());
    }

    @Test
    void watch_isIdempotent() {
        var loc = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(loc));
        when(watchedRepository.findByUserIdAndMarketCnpj(user.getId(), "11111111000111"))
                .thenReturn(Optional.of(UserWatchedMarket.builder().user(user).marketCnpj("11111111000111").build()));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var response = service.watch(user, "11111111000111");

        assertTrue(response.watching());
        verify(watchedRepository, never()).save(any()); // already pinned → no insert
    }

    @Test
    void unwatch_delegatesToRepo() {
        service.unwatch(user, "11111111000111");
        verify(watchedRepository).deleteByUserIdAndMarketCnpj(user.getId(), "11111111000111");
    }

    @Test
    void watch_rejectsMalformedCnpjWith400() {
        assertThrows(InvalidCnpjException.class, () -> service.watch(user, "123"));
        verify(marketRepository, never()).findByCnpj(any());
        verify(watchedRepository, never()).save(any());
    }

    @Test
    void unwatch_rejectsMalformedCnpjWith400() {
        assertThrows(InvalidCnpjException.class, () -> service.unwatch(user, "not-a-cnpj"));
        verify(watchedRepository, never()).deleteByUserIdAndMarketCnpj(any(), any());
    }

    @Test
    void watch_normalizesFormattedCnpjBeforeLookup() {
        var location = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(location));
        when(watchedRepository.findByUserIdAndMarketCnpj(user.getId(), "11111111000111"))
                .thenReturn(Optional.empty());
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var response = service.watch(user, "11.111.111/0001-11");

        assertTrue(response.watching());
        verify(watchedRepository).save(any());
    }

    @Test
    void watchedCnpjs_returnsSet() {
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        ));

        var set = service.watchedCnpjs(user);

        assertEquals(2, set.size());
        assertTrue(set.contains("11111111000111"));
    }

    @Test
    void listWatched_returnsHydratedRowsAndFlagsVisited() {
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                market("11111111000111", "Mercado A"),
                market("22222222000111", "Mercado B")
        ));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of("11111111000111"));

        var rows = service.listWatched(user);

        assertEquals(2, rows.size());
        var a = rows.stream().filter(r -> r.cnpj().equals("11111111000111")).findFirst().orElseThrow();
        var b = rows.stream().filter(r -> r.cnpj().equals("22222222000111")).findFirst().orElseThrow();
        assertTrue(a.visited());
        assertFalse(b.visited());
        assertTrue(a.watching());
        assertTrue(b.watching());
    }

    @Test
    void listMarkets_setsFriendlyNameToCleanedName_keepsRawLegalName() {
        // Regression: /markets used to return the raw razão social as friendlyName. It must now go
        // through the MarketNameService cascade (→ cleanLegalName), while `name` keeps the legal string.
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                market("11111111000111", "BISTEK SUPERMERCADOS LTDA"),
                market("22222222000111", "DIMED S/A DISTRIBUIDORA DE MEDICAMENTOS")
        ));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());

        var rows = service.listMarkets(user, MarketScope.MINE, null, null);

        var bistek = rows.stream().filter(row -> row.cnpj().equals("11111111000111")).findFirst().orElseThrow();
        var dimed = rows.stream().filter(row -> row.cnpj().equals("22222222000111")).findFirst().orElseThrow();
        assertEquals("Bistek", bistek.friendlyName());
        assertEquals("BISTEK SUPERMERCADOS LTDA", bistek.name());
        // "DISTRIBUIDORA DE MEDICAMENTOS" isn't corporate boilerplate, so it stays — only "S/A" is
        // stripped. The point: the legal "DIMED S/A" prefix is gone and the string is title-cased.
        assertEquals("Dimed Distribuidora de Medicamentos", dimed.friendlyName());
        assertEquals("DIMED S/A DISTRIBUIDORA DE MEDICAMENTOS", dimed.name());
    }

    @Test
    void listMarkets_mineReturnsOnlyWatched() {
        // Household visited a market it does NOT watch — MINE must exclude it.
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of("33333333000111"));
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(market("11111111000111", "Watched")));

        var rows = service.listMarkets(user, MarketScope.MINE, null, null);

        assertEquals(List.of("11111111000111"), rows.stream().map(row -> row.cnpj()).toList());
        verify(marketRepository, never()).findAll();
    }

    @Test
    void watch_savesWhenNotYetPinned() {
        var location = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(location));
        when(watchedRepository.findByUserIdAndMarketCnpj(user.getId(), "11111111000111"))
                .thenReturn(Optional.empty());
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of("11111111000111"));

        var response = service.watch(user, "11111111000111");

        assertTrue(response.watching());
        assertTrue(response.visited());
        verify(watchedRepository).save(any());
    }

    @Test
    void listWatched_emptyWatchlistReturnsEmptyList() {
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of());
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());

        assertTrue(service.listWatched(user).isEmpty());
    }

    @Test
    void listWatched_unhydratedEntryWhenLocationMissing() {
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("99999999000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of());
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var rows = service.listWatched(user);

        assertEquals(1, rows.size());
        var only = rows.get(0);
        assertEquals("99999999000111", only.cnpj());
        assertNull(only.name());
        assertNull(only.distanceKm());
        assertFalse(only.visited());
        assertTrue(only.watching());
    }

    @Test
    void listWatched_computesDistanceFromResolvedOrigin() {
        when(viewerLocationService.resolve(any(), isNull(), isNull())).thenReturn(new ViewerLocation(
                new BigDecimal("-30.0300000"), new BigDecimal("-51.2300000"), Source.PROFILE, "RS"));
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any()))
                .thenReturn(List.of(market("11111111000111", "Mercado A")));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var rows = service.listWatched(user);

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).distanceKm() != null && rows.get(0).distanceKm() >= 0);
    }

    @Test
    void listMarkets_allUnionsNearbyWithinRadiusAndWatched() {
        when(viewerLocationService.resolve(any(), isNull(), isNull())).thenReturn(new ViewerLocation(
                new BigDecimal("-30.0500000"), new BigDecimal("-51.2200000"), Source.PROFILE, "RS"));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of());
        lenient().when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of());
        var nearMarket = market("44444444000111", "Near");
        var farMarket = MarketLocation.builder()
                .cnpj("55555555000111").cnpjRoot("55555555").name("Far")
                .latitude(new BigDecimal("-10.0000000")).longitude(new BigDecimal("-40.0000000"))
                .build();
        when(marketRepository.findAll()).thenReturn(List.of(nearMarket, farMarket));

        var rows = service.listMarkets(user, MarketScope.ALL, null, null);

        assertEquals(1, rows.size());
        assertEquals("44444444000111", rows.get(0).cnpj());
    }

    @Test
    void listMarkets_allKeepsWatchedFarMarketButSortsItLast() {
        when(viewerLocationService.resolve(any(), isNull(), isNull())).thenReturn(new ViewerLocation(
                new BigDecimal("-30.0500000"), new BigDecimal("-51.2200000"), Source.PROFILE, "RS"));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("55555555000111").build()
        ));
        var nearMarket = market("44444444000111", "Near");
        var farWatched = MarketLocation.builder()
                .cnpj("55555555000111").cnpjRoot("55555555").name("Far Watched")
                .latitude(new BigDecimal("-10.0000000")).longitude(new BigDecimal("-40.0000000"))
                .build();
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(farWatched));
        when(marketRepository.findAll()).thenReturn(List.of(nearMarket, farWatched));

        var rows = service.listMarkets(user, MarketScope.ALL, null, null);

        assertEquals(2, rows.size());
        // Near market first (distance asc), the far watched one last — not dropped.
        assertEquals("44444444000111", rows.get(0).cnpj());
        assertEquals("55555555000111", rows.get(1).cnpj());
        assertTrue(rows.get(1).watching());
    }

    @Test
    void listMarkets_allUnlocatedViewerGetsOnlyWatched() {
        when(viewerLocationService.resolve(any(), isNull(), isNull()))
                .thenReturn(new ViewerLocation(null, null, Source.NONE, "RS"));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        assertTrue(service.listMarkets(user, MarketScope.ALL, null, null).isEmpty());
        verify(marketRepository, never()).findAll();
    }

    @Test
    void listMarkets_devicePositionIsForwardedToResolver() {
        var deviceLat = new BigDecimal("-30.0500000");
        var deviceLng = new BigDecimal("-51.2200000");
        when(viewerLocationService.resolve(user, deviceLat, deviceLng)).thenReturn(new ViewerLocation(
                deviceLat, deviceLng, Source.DEVICE, "RS"));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of());
        when(marketRepository.findAll()).thenReturn(List.of(market("44444444000111", "Near")));

        var rows = service.listMarkets(user, MarketScope.ALL, deviceLat, deviceLng);

        assertEquals(1, rows.size());
        verify(viewerLocationService).resolve(user, deviceLat, deviceLng);
    }

    @Test
    void watch_throwsPaywallWhenFreeUserAtLimit() {
        var location = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(location));
        when(watchedRepository.findByUserIdAndMarketCnpj(user.getId(), "11111111000111"))
                .thenReturn(Optional.empty());
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("aaaaaaaa000111").build(),
                UserWatchedMarket.builder().marketCnpj("bbbbbbbb000111").build(),
                UserWatchedMarket.builder().marketCnpj("cccccccc000111").build()
        ));
        doThrow(new PaywallException(Feature.WATCHED_MARKETS_UNLIMITED.name()))
                .when(subscriptionGate).requireWithinLimit(user, Feature.WATCHED_MARKETS_UNLIMITED, 3L);

        assertThrows(PaywallException.class, () -> service.watch(user, "11111111000111"));
        verify(watchedRepository, never()).save(any());
    }

    @Test
    void watch_allowsRepinOfExistingEvenAtLimit() {
        var location = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(location));
        when(watchedRepository.findByUserIdAndMarketCnpj(user.getId(), "11111111000111"))
                .thenReturn(Optional.of(UserWatchedMarket.builder().user(user).marketCnpj("11111111000111").build()));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var response = service.watch(user, "11111111000111");

        assertTrue(response.watching());
        verify(watchedRepository, never()).save(any()); // existing pin → no cap check, no insert
    }

    private MarketLocation market(String cnpj, String name) {
        return MarketLocation.builder()
                .cnpj(cnpj).cnpjRoot(cnpj.substring(0, 8)).name(name)
                .latitude(new BigDecimal("-30.0500000")).longitude(new BigDecimal("-51.2200000"))
                .build();
    }
}
