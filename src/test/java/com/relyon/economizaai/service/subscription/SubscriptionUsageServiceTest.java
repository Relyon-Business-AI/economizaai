package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.UserWatchedMarket;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserWatchedMarketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionUsageServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private HouseholdCustomCategoryRepository customCategoryRepository;
    @Mock private UserWatchedMarketRepository watchedMarketRepository;

    private SubscriptionUsageService usageService;
    private User user;
    private UUID householdId;

    @BeforeEach
    void setUp() {
        usageService = new SubscriptionUsageService(
                receiptRepository, customCategoryRepository, watchedMarketRepository, new CollaborativeProperties());
        householdId = UUID.randomUUID();
        user = User.builder().id(UUID.randomUUID())
                .household(Household.builder().id(householdId).build()).build();
    }

    private static UserWatchedMarket pinOf(String marketCnpj) {
        return UserWatchedMarket.builder().marketCnpj(marketCnpj).build();
    }

    @Test
    void usageOf_pairsEachCountWithItsFreeCap() {
        when(receiptRepository.countByUserIdAndSplitCopyFalseAndCreatedAtGreaterThanEqual(eq(user.getId()), any()))
                .thenReturn(12L);
        when(customCategoryRepository.countByHouseholdId(householdId)).thenReturn(2L);
        when(watchedMarketRepository.findAllByUserHouseholdId(householdId)).thenReturn(List.of(
                pinOf("11111111000111"), pinOf("22222222000122"), pinOf("33333333000133"),
                pinOf("44444444000144"), pinOf("11111111000111")));
        when(receiptRepository.findOldestConfirmedIssuedAt(householdId))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 3, 9, 18, 30)));

        var usage = usageService.usageOf(user);

        assertEquals(12, usage.receiptsThisMonth());
        assertEquals(3, usage.freeMonthlyReceipts());
        assertEquals(2, usage.customCategories());
        assertEquals(1, usage.freeCustomCategories());
        assertEquals(4, usage.watchedMarkets());
        assertEquals(3, usage.freeWatchedMarkets());
        assertEquals(LocalDate.of(2026, 3, 9), usage.oldestPurchaseDate());
        assertEquals(90, usage.freeHistoryDays());
    }

    @Test
    void usageOf_hasNoOldestPurchaseWithoutConfirmedReceipts() {
        when(receiptRepository.findOldestConfirmedIssuedAt(householdId)).thenReturn(Optional.empty());

        assertNull(usageService.usageOf(user).oldestPurchaseDate());
    }
}
