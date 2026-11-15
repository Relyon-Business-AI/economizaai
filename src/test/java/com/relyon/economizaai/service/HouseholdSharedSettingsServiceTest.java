package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.UserWatchedMarket;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.repository.UserWatchedMarketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdSharedSettingsServiceTest {

    private static final Household HOUSEHOLD = Household.builder().id(UUID.randomUUID()).build();

    @Mock private UserRepository userRepository;
    @Mock private UserWatchedMarketRepository watchedMarketRepository;
    @Mock private NotificationRuleRepository ruleRepository;
    @InjectMocks private HouseholdSharedSettingsService service;

    @Captor private ArgumentCaptor<List<UserWatchedMarket>> copiedPins;
    @Captor private ArgumentCaptor<List<NotificationRule>> copiedAlerts;

    private User member() {
        return User.builder().id(UUID.randomUUID()).email("member@economizaai.app").household(HOUSEHOLD).build();
    }

    private User memberWithHome(String latitude, String longitude, LocalDateTime homeSetAt) {
        var member = member();
        member.setHomeLatitude(new BigDecimal(latitude));
        member.setHomeLongitude(new BigDecimal(longitude));
        member.setHomeSetAt(homeSetAt);
        return member;
    }

    private void householdHasMembers(User... members) {
        when(userRepository.findAllByHouseholdId(HOUSEHOLD.getId())).thenReturn(List.of(members));
    }

    private UserWatchedMarket pin(User owner, String marketCnpj) {
        return UserWatchedMarket.builder().user(owner).marketCnpj(marketCnpj).build();
    }

    private NotificationRule alert(User owner, Product product, String thresholdPrice) {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(owner).type(NotificationType.PRICE_DROP).product(product)
                .thresholdPrice(new BigDecimal(thresholdPrice)).radiusKm(15.0).active(true).build();
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).build();
    }

    // ---- home location ----

    @Test
    void saveHomeLocationForHousehold_givesEveryMemberTheCoarsenedPoint() {
        var savingMember = member();
        var otherMember = memberWithHome("-23.550", "-46.633", LocalDateTime.now().minusDays(30));
        householdHasMembers(savingMember, otherMember);

        var saved = service.saveHomeLocationForHousehold(
                savingMember, new BigDecimal("-30.0277431"), new BigDecimal("-51.2287219"));

        assertSame(savingMember, saved);
        for (var member : List.of(savingMember, otherMember)) {
            assertEquals(0, member.getHomeLatitude().compareTo(new BigDecimal("-30.028")));
            assertEquals(0, member.getHomeLongitude().compareTo(new BigDecimal("-51.229")));
            assertNotNull(member.getHomeSetAt());
        }
        verify(userRepository).saveAll(List.of(savingMember, otherMember));
    }

    @Test
    void shareMostRecentHomeLocation_givesEveryMemberThePointSavedLast() {
        var olderHome = memberWithHome("-23.550", "-46.633", LocalDateTime.of(2026, 1, 10, 9, 0));
        var newerHome = memberWithHome("-30.028", "-51.229", LocalDateTime.of(2026, 11, 2, 9, 0));
        var withoutHome = member();
        householdHasMembers(olderHome, newerHome, withoutHome);

        service.shareMostRecentHomeLocation(HOUSEHOLD.getId());

        for (var member : List.of(olderHome, newerHome, withoutHome)) {
            assertEquals(new BigDecimal("-30.028"), member.getHomeLatitude());
            assertEquals(new BigDecimal("-51.229"), member.getHomeLongitude());
            assertEquals(LocalDateTime.of(2026, 11, 2, 9, 0), member.getHomeSetAt());
        }
        verify(userRepository).saveAll(List.of(olderHome, newerHome, withoutHome));
    }

    @Test
    void shareMostRecentHomeLocation_changesNothingWhenNoMemberSavedAPoint() {
        var withoutHome = member();
        householdHasMembers(withoutHome, member());

        service.shareMostRecentHomeLocation(HOUSEHOLD.getId());

        assertNull(withoutHome.getHomeLatitude());
        verify(userRepository, never()).saveAll(any());
    }

    // ---- leaving member ----

    @Test
    void copySharedSettingsToLeavingMember_copiesTheMarketsOtherMembersPinned() {
        var leavingMember = member();
        var stayingMember = member();
        when(watchedMarketRepository.findAllByUserHouseholdId(HOUSEHOLD.getId())).thenReturn(List.of(
                pin(leavingMember, "11111111000111"),
                pin(stayingMember, "11111111000111"),
                pin(stayingMember, "22222222000122")));
        when(ruleRepository.findAllByHouseholdIdAndTypeFetchProduct(HOUSEHOLD.getId(), NotificationType.PRICE_DROP))
                .thenReturn(List.of());

        service.copySharedSettingsToLeavingMember(leavingMember, HOUSEHOLD.getId());

        verify(watchedMarketRepository).saveAll(copiedPins.capture());
        assertEquals(1, copiedPins.getValue().size());
        assertEquals("22222222000122", copiedPins.getValue().get(0).getMarketCnpj());
        assertSame(leavingMember, copiedPins.getValue().get(0).getUser());
    }

    @Test
    void copySharedSettingsToLeavingMember_copiesTheAlertsOtherMembersCreated() {
        var leavingMember = member();
        var stayingMember = member();
        var milk = product("Leite 1L");
        var rice = product("Arroz 5kg");
        when(watchedMarketRepository.findAllByUserHouseholdId(HOUSEHOLD.getId())).thenReturn(List.of());
        when(ruleRepository.findAllByHouseholdIdAndTypeFetchProduct(HOUSEHOLD.getId(), NotificationType.PRICE_DROP))
                .thenReturn(List.of(
                        alert(stayingMember, milk, "4.00"),
                        alert(leavingMember, milk, "5.00"),
                        alert(stayingMember, rice, "20.00")));

        service.copySharedSettingsToLeavingMember(leavingMember, HOUSEHOLD.getId());

        verify(ruleRepository).saveAll(copiedAlerts.capture());
        assertEquals(1, copiedAlerts.getValue().size());
        var copiedAlert = copiedAlerts.getValue().get(0);
        assertSame(leavingMember, copiedAlert.getUser());
        assertSame(rice, copiedAlert.getProduct());
        assertEquals(new BigDecimal("20.00"), copiedAlert.getThresholdPrice());
        assertEquals(15.0, copiedAlert.getRadiusKm());
        assertNull(copiedAlert.getId());
    }
}
