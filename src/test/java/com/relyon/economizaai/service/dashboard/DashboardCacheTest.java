package com.relyon.economizaai.service.dashboard;

import com.relyon.economizaai.dto.response.SpendInsightsResponse;
import com.relyon.economizaai.dto.response.SuggestedShoppingListResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.InsightsService;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code dashboard} cache: the expensive core is computed once and
 * served from cache on repeat, and a household-generation bump invalidates it.
 */
@SpringBootTest
@ActiveProfiles("test")
class DashboardCacheTest {

    @Autowired private DashboardCacheService dashboardCacheService;
    @Autowired private HouseholdCacheGen householdCacheGen;
    @Autowired private CacheManager cacheManager;

    @MockitoBean private InsightsService insightsService;
    @MockitoBean private ReceiptRepository receiptRepository;
    @MockitoBean private ConsumptionIntelligenceService consumptionService;
    @MockitoBean private CommunityPromoService communityPromoService;
    @MockitoBean private WatchedMarketService watchedMarketService;

    private User user;

    @BeforeEach
    void setUp() {
        cacheManager.getCache("dashboard").clear();
        user = User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
        when(insightsService.spend(eq(user), any(), any(), any())).thenReturn(
                new SpendInsightsResponse(null, null, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of()));
        when(consumptionService.suggestedList(user, false, 0))
                .thenReturn(new SuggestedShoppingListResponse(List.of(), null));
        when(communityPromoService.detectAll(any(), any(), any(), any())).thenReturn(List.of());
        when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of());
        when(receiptRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
    }

    @Test
    void core_isServedFromCacheOnRepeat() {
        dashboardCacheService.buildCachedDashboard(user, MarketScope.ALL);
        dashboardCacheService.buildCachedDashboard(user, MarketScope.ALL);
        verify(insightsService, times(1)).spend(eq(user), any(), any(), any());
    }

    @Test
    void bumpingHouseholdGeneration_invalidatesCore() {
        dashboardCacheService.buildCachedDashboard(user, MarketScope.ALL);
        householdCacheGen.bump(user.getHousehold().getId());
        dashboardCacheService.buildCachedDashboard(user, MarketScope.ALL);
        verify(insightsService, times(2)).spend(eq(user), any(), any(), any());
    }

    @Test
    void bumpingAnotherHousehold_keepsCoreCached() {
        dashboardCacheService.buildCachedDashboard(user, MarketScope.ALL);
        householdCacheGen.bump(UUID.randomUUID());
        dashboardCacheService.buildCachedDashboard(user, MarketScope.ALL);
        verify(insightsService, times(1)).spend(eq(user), any(), any(), any());
    }

    @Test
    void differentUsers_getDistinctCacheEntries() {
        var housemate = User.builder().id(UUID.randomUUID()).email("h@e")
                .household(user.getHousehold())
                .build();
        when(insightsService.spend(eq(housemate), any(), any(), any())).thenReturn(
                new SpendInsightsResponse(null, null, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of()));
        when(consumptionService.suggestedList(housemate, false, 0))
                .thenReturn(new SuggestedShoppingListResponse(List.of(), null));

        dashboardCacheService.buildCachedDashboard(user, MarketScope.ALL);
        dashboardCacheService.buildCachedDashboard(housemate, MarketScope.ALL);

        verify(insightsService, times(1)).spend(eq(user), any(), any(), any());
        verify(insightsService, times(1)).spend(eq(housemate), any(), any(), any());
    }
}
