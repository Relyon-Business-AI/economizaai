package com.relyon.economizaai.service.dashboard;

import com.relyon.economizaai.dto.response.DashboardResponse;
import com.relyon.economizaai.dto.response.DashboardResponse.SpendSnapshot;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.notifications.NotificationInboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private DashboardCacheService dashboardCacheService;
    @Mock private NotificationInboxService notificationInboxService;
    @InjectMocks private DashboardService service;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private DashboardResponse core() {
        return new DashboardResponse(
                new SpendSnapshot(2026, 6, new BigDecimal("100.00"), new BigDecimal("4.00"), 2, new BigDecimal("50.00")),
                List.of(), List.of(), List.of(),
                0L, // placeholder unread from the cached core
                LocalDateTime.of(2026, Month.JUNE, 6, 10, 0));
    }

    @Test
    void build_passesAllCachedSectionsThroughUnchanged() {
        var user = user();
        var core = core();
        when(dashboardCacheService.buildCachedDashboard(eq(user), any())).thenReturn(core);
        when(notificationInboxService.unreadCount(user)).thenReturn(7L);

        var response = service.build(user);

        assertEquals(core.currentMonth(), response.currentMonth());
        assertEquals(core.recentReceipts(), response.recentReceipts());
        assertEquals(core.suggestedShoppingList(), response.suggestedShoppingList());
        assertEquals(core.communityPromosNearby(), response.communityPromosNearby());
        assertEquals(core.generatedAt(), response.generatedAt());
        assertEquals(7L, response.unreadNotificationCount(), "placeholder unread is replaced by the live count");
    }

    @Test
    void build_emptyHousehold_returnsEmptySectionsNotErrors() {
        var user = user();
        var emptyCore = new DashboardResponse(
                new SpendSnapshot(2026, 6, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO),
                List.of(), List.of(), List.of(), 0L,
                LocalDateTime.of(2026, Month.JUNE, 6, 10, 0));
        when(dashboardCacheService.buildCachedDashboard(eq(user), any())).thenReturn(emptyCore);
        when(notificationInboxService.unreadCount(user)).thenReturn(0L);

        var response = service.build(user);

        assertEquals(0, response.currentMonth().total().compareTo(BigDecimal.ZERO));
        assertEquals(0L, response.currentMonth().receiptCount());
        assertEquals(List.of(), response.recentReceipts());
        assertEquals(List.of(), response.suggestedShoppingList());
        assertEquals(List.of(), response.communityPromosNearby());
        assertEquals(0L, response.unreadNotificationCount());
    }

    @Test
    void unreadCount_isAlwaysLive_evenWhenCoreIsCached() {
        var user = user();
        when(dashboardCacheService.buildCachedDashboard(eq(user), any())).thenReturn(core());
        // unread changes between calls (user read a notification); core stays cached
        when(notificationInboxService.unreadCount(user)).thenReturn(3L, 0L);

        var first = service.build(user);
        var second = service.build(user);

        assertEquals(3L, first.unreadNotificationCount());
        assertEquals(0L, second.unreadNotificationCount(), "unread is re-fetched, not served from the cached core");
        // the cached sections are identical
        assertEquals(first.currentMonth(), second.currentMonth());
    }
}
