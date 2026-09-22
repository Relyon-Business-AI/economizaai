package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code insightsSpend} cache: a repeated query hits the cache
 * (repository called once), and bumping the household generation invalidates it
 * (repository called again).
 */
@SpringBootTest
@ActiveProfiles("test")
class InsightsSpendCacheTest {

    @Autowired private InsightsService insightsService;
    @Autowired private HouseholdCacheGen householdCacheGen;
    @Autowired private CacheManager cacheManager;

    @MockitoBean private InsightsRepository insightsRepository;

    private User user;
    private final LocalDateTime from = LocalDateTime.of(2026, Month.JUNE, 1, 0, 0);
    private final LocalDateTime to = LocalDateTime.of(2026, Month.JUNE, 30, 23, 59, 59);

    @BeforeEach
    void setUp() {
        cacheManager.getCache("insightsSpend").clear();
        user = User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
        when(insightsRepository.totalSpend(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(insightsRepository.spendByMonth(any(), any(), any())).thenReturn(List.of());
        when(insightsRepository.spendByWeek(any(), any(), any())).thenReturn(List.of());
        when(insightsRepository.spendByMarket(any(), any(), any())).thenReturn(List.of());
        when(insightsRepository.spendByCategory(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void repeatedQuery_servedFromCache() {
        insightsService.spend(user, from, to);
        insightsService.spend(user, from, to);

        verify(insightsRepository, times(1))
                .totalSpend(eq(user.getHousehold().getId()), any(), any());
    }

    @Test
    void bumpingHouseholdGeneration_invalidatesCache() {
        insightsService.spend(user, from, to);
        householdCacheGen.bump(user.getHousehold().getId());
        insightsService.spend(user, from, to);

        verify(insightsRepository, times(2))
                .totalSpend(eq(user.getHousehold().getId()), any(), any());
    }
}
