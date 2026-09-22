package com.relyon.economizaai.service.geo;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private WatchedMarketService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new WatchedMarketService(watchedRepository, marketRepository, receiptRepository,
                marketNameService, subscriptionGate);
        // Default: PRO-equivalent unlimited cap so existing tests keep their intent.
        lenient().when(subscriptionGate.watchedMarketLimit(any())).thenReturn(Integer.MAX_VALUE);
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
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
        var pinned = List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        );
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(pinned);
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
    void listForPicker_putsWatchedFirstThenVisited() {
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of("33333333000111"));
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                market("11111111000111", "Watched"),
                market("33333333000111", "Visited")
        ));

        var rows = service.listForPicker(user, null);

        assertEquals(2, rows.size());
        assertEquals("11111111000111", rows.get(0).cnpj()); // watched first
        assertEquals("33333333000111", rows.get(1).cnpj()); // then visited
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
        assertEquals(null, only.name());
        assertEquals(null, only.distanceKm());
        assertFalse(only.visited());
        assertTrue(only.watching());
    }

    @Test
    void listWatched_computesDistanceWhenHomeSet() {
        user.setHomeLatitude(new BigDecimal("-30.0300000"));
        user.setHomeLongitude(new BigDecimal("-51.2300000"));
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any()))
                .thenReturn(List.of(market("11111111000111", "Mercado A")));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        var rows = service.listWatched(user);

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).distanceKm() != null && rows.get(0).distanceKm() > 0);
    }

    @Test
    void listForPicker_includesNearbyGeocodedMarketsWithinRadius() {
        user.setHomeLatitude(new BigDecimal("-30.0500000"));
        user.setHomeLongitude(new BigDecimal("-51.2200000"));
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of());
        lenient().when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of());
        var nearMarket = market("44444444000111", "Near");
        var farMarket = MarketLocation.builder()
                .cnpj("55555555000111").cnpjRoot("55555555").name("Far")
                .latitude(new BigDecimal("-10.0000000")).longitude(new BigDecimal("-40.0000000"))
                .build();
        when(marketRepository.findAll()).thenReturn(List.of(nearMarket, farMarket));

        var rows = service.listForPicker(user, 5.0);

        assertEquals(1, rows.size());
        assertEquals("44444444000111", rows.get(0).cnpj());
    }

    @Test
    void listForPicker_noRadiusSkipsNearbyLookup() {
        when(receiptRepository.findDistinctCnpjsByHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        var rows = service.listForPicker(user, null);

        assertTrue(rows.isEmpty());
        verify(marketRepository, never()).findAll();
    }

    @Test
    void watch_throwsPaywallWhenFreeUserAtLimit() {
        var location = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(location));
        when(watchedRepository.findByUserIdAndMarketCnpj(user.getId(), "11111111000111"))
                .thenReturn(Optional.empty());
        when(subscriptionGate.watchedMarketLimit(any())).thenReturn(3);
        when(watchedRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("aaaaaaaa000111").build(),
                UserWatchedMarket.builder().marketCnpj("bbbbbbbb000111").build(),
                UserWatchedMarket.builder().marketCnpj("cccccccc000111").build()
        ));

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
