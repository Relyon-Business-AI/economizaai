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
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.MerchantSupportOverride;
import com.relyon.economizaai.dto.response.MarketResponse;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ReceiptRepository.HouseholdMarketPurchases;
import com.relyon.economizaai.repository.ReceiptRepository.MarketPurchaseCount;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    @Mock private CnpjActivityClient cnpjActivityClient;

    private WatchedMarketService service;
    private User user;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        properties.getCollaborative().setBestMarketsNearbyRadiusKm(10);
        service = new WatchedMarketService(watchedRepository, marketRepository, receiptRepository,
                marketNameService, subscriptionGate, viewerLocationService, properties,
                new MerchantSupportGate(marketRepository, cnpjActivityClient));
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
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId()))
                .thenReturn(List.of(UserWatchedMarket.builder().user(user).marketCnpj("11111111000111").build()));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var response = service.watch(user, "11111111000111");

        assertTrue(response.watching());
        verify(watchedRepository, never()).save(any()); // already pinned → no insert
    }

    @Test
    void watch_doesNotPinAgainAMarketAnotherMemberAlreadyPinned() {
        var otherMember = User.builder().id(UUID.randomUUID()).household(user.getHousehold()).build();
        when(marketRepository.findByCnpj("11111111000111"))
                .thenReturn(Optional.of(market("11111111000111", "Mercado A")));
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId()))
                .thenReturn(List.of(UserWatchedMarket.builder().user(otherMember).marketCnpj("11111111000111").build()));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var response = service.watch(user, "11111111000111");

        assertTrue(response.watching());
        verify(watchedRepository, never()).save(any());
    }

    @Test
    void watchedCnpjs_areTheOnesPinnedByAnyMemberOfTheHousehold() {
        var otherMember = User.builder().id(UUID.randomUUID()).household(user.getHousehold()).build();
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
                UserWatchedMarket.builder().user(user).marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().user(otherMember).marketCnpj("22222222000122").build(),
                UserWatchedMarket.builder().user(otherMember).marketCnpj("11111111000111").build()));

        assertEquals(Set.of("11111111000111", "22222222000122"), service.watchedCnpjs(user));
    }

    @Test
    void unwatch_delegatesToRepo() {
        service.unwatch(user, "11111111000111");
        verify(watchedRepository).deleteByUserHouseholdIdAndMarketCnpj(user.getHousehold().getId(), "11111111000111");
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
        verify(watchedRepository, never()).deleteByUserHouseholdIdAndMarketCnpj(any(), any());
    }

    @Test
    void watch_normalizesFormattedCnpjBeforeLookup() {
        var location = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(location));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var response = service.watch(user, "11.111.111/0001-11");

        assertTrue(response.watching());
        verify(watchedRepository).save(any());
    }

    @Test
    void watchedCnpjs_returnsSet() {
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        ));

        var set = service.watchedCnpjs(user);

        assertEquals(2, set.size());
        assertTrue(set.contains("11111111000111"));
    }

    @Test
    void listWatched_returnsHydratedRowsAndFlagsVisited() {
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
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
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
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
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
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
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of("11111111000111"));

        var response = service.watch(user, "11111111000111");

        assertTrue(response.watching());
        assertTrue(response.visited());
        verify(watchedRepository).save(any());
    }

    @Test
    void listWatched_emptyWatchlistReturnsEmptyList() {
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of());
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());

        assertTrue(service.listWatched(user).isEmpty());
    }

    @Test
    void listWatched_unhydratedEntryWhenLocationMissing() {
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
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
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
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
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of());
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
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
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
    void listMarkets_allLeavesOutNearbyMerchantsThatAreNotGroceryOrPharmacy() {
        when(viewerLocationService.resolve(any(), isNull(), isNull())).thenReturn(new ViewerLocation(
                new BigDecimal("-30.0500000"), new BigDecimal("-51.2200000"), Source.PROFILE, "RS"));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of());
        var adminApprovedShop = market("77777777000111", "Emporio", MerchantSegment.OTHER);
        adminApprovedShop.setSupportOverride(MerchantSupportOverride.SUPPORTED);
        when(marketRepository.findAll()).thenReturn(List.of(
                market("11111111000111", "Supermercado", MerchantSegment.SUPERMARKET),
                market("22222222000111", "Farmacia", MerchantSegment.PHARMACY),
                market("33333333000111", "Padaria", MerchantSegment.FOOD_RETAIL),
                market("44444444000111", "Restaurante", MerchantSegment.FOOD_SERVICE),
                market("55555555000111", "Joalheria", MerchantSegment.OTHER),
                market("66666666000111", "Sem classificacao", MerchantSegment.UNKNOWN),
                adminApprovedShop));

        var listedCnpjs = service.listMarkets(user, MarketScope.ALL, null, null).stream()
                .map(MarketResponse::cnpj)
                .collect(Collectors.toSet());

        assertEquals(Set.of("11111111000111", "22222222000111", "33333333000111", "77777777000111"), listedCnpjs);
    }

    @Test
    void listMarkets_allKeepsWatchedMerchantThatIsNotGroceryOrPharmacy() {
        when(viewerLocationService.resolve(any(), isNull(), isNull())).thenReturn(new ViewerLocation(
                new BigDecimal("-30.0500000"), new BigDecimal("-51.2200000"), Source.PROFILE, "RS"));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("44444444000111").build()));
        var watchedRestaurant = market("44444444000111", "Restaurante", MerchantSegment.FOOD_SERVICE);
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(watchedRestaurant));
        when(marketRepository.findAll()).thenReturn(List.of(watchedRestaurant));

        var rows = service.listMarkets(user, MarketScope.ALL, null, null);

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).watching());
    }

    @Test
    void listMarkets_allUnlocatedViewerGetsOnlyWatched() {
        when(viewerLocationService.resolve(any(), isNull(), isNull()))
                .thenReturn(new ViewerLocation(null, null, Source.NONE, "RS"));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of());

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
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of());
        when(marketRepository.findAll()).thenReturn(List.of(market("44444444000111", "Near")));

        var rows = service.listMarkets(user, MarketScope.ALL, deviceLat, deviceLng);

        assertEquals(1, rows.size());
        verify(viewerLocationService).resolve(user, deviceLat, deviceLng);
    }

    @Test
    void watch_throwsPaywallWhenFreeUserAtLimit() {
        var location = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(location));
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
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
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId()))
                .thenReturn(List.of(UserWatchedMarket.builder().user(user).marketCnpj("11111111000111").build()));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var response = service.watch(user, "11111111000111");

        assertTrue(response.watching());
        verify(watchedRepository, never()).save(any()); // existing pin → no cap check, no insert
    }

    @Test
    void listMarkets_communityPurchaseCountRespectsKAnonymity() {
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId())).thenReturn(List.of());
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                market("11111111000111", "Popular"), market("22222222000111", "Pouco Visitado")));
        when(receiptRepository.countConfirmedPurchasesByCnpjIn(any())).thenReturn(List.of(
                purchaseCount("11111111000111", 148, 3),
                purchaseCount("22222222000111", 40, 2)));

        var rows = service.listMarkets(user, MarketScope.MINE, null, null);

        var countByCnpj = rows.stream().collect(Collectors.toMap(
                row -> row.cnpj(), row -> Optional.ofNullable(row.communityPurchaseCount())));
        assertEquals(Optional.of(148L), countByCnpj.get("11111111000111"));
        // Two households only: below K=3, so the count stays undisclosed.
        assertEquals(Optional.empty(), countByCnpj.get("22222222000111"));
    }

    @Test
    void listMarkets_carriesHouseholdOwnPurchasesOnlyWhereItBought() {
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of("11111111000111"));
        when(watchedRepository.findAllByUserHouseholdId(user.getHousehold().getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                market("11111111000111", "Frequentado"), market("22222222000111", "Nunca Fui")));
        when(receiptRepository.sumHouseholdPurchasesByCnpjIn(any(), any())).thenReturn(List.of(
                new HouseholdMarketPurchases() {
                    @Override public String getCnpj() { return "11111111000111"; }
                    @Override public long getReceiptCount() { return 9; }
                    @Override public BigDecimal getTotalSpent() { return new BigDecimal("612.4"); }
                }));

        var rows = service.listMarkets(user, MarketScope.MINE, null, null);

        var visited = rows.stream().filter(row -> row.cnpj().equals("11111111000111")).findFirst().orElseThrow();
        assertEquals(9L, visited.householdPurchaseCount());
        assertEquals(new BigDecimal("612.40"), visited.householdSpend());
        var neverVisited = rows.stream().filter(row -> row.cnpj().equals("22222222000111")).findFirst().orElseThrow();
        assertNull(neverVisited.householdPurchaseCount());
        assertNull(neverVisited.householdSpend());
    }

    private MarketPurchaseCount purchaseCount(String cnpj, long receiptCount, long householdCount) {
        return new MarketPurchaseCount() {
            @Override public String getCnpj() { return cnpj; }
            @Override public long getReceiptCount() { return receiptCount; }
            @Override public long getHouseholdCount() { return householdCount; }
        };
    }

    private MarketLocation market(String cnpj, String name) {
        return market(cnpj, name, MerchantSegment.SUPERMARKET);
    }

    private MarketLocation market(String cnpj, String name, MerchantSegment segment) {
        return MarketLocation.builder()
                .cnpj(cnpj).cnpjRoot(cnpj.substring(0, 8)).name(name).segment(segment)
                .latitude(new BigDecimal("-30.0500000")).longitude(new BigDecimal("-51.2200000"))
                .build();
    }
}
