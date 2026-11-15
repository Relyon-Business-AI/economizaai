package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.SubscriptionUsageResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.UserWatchedMarket;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserWatchedMarketRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Usage of the FREE-capped features, counted exactly as the paywalls count it
 * ({@link SubscriptionGateService}), so the Premium screen and the caps never disagree.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionUsageService {

    private final ReceiptRepository receiptRepository;
    private final HouseholdCustomCategoryRepository customCategoryRepository;
    private final UserWatchedMarketRepository watchedMarketRepository;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public SubscriptionUsageResponse usageOf(User user) {
        var householdId = user.getHousehold().getId();
        var freeCaps = properties.getSubscription().getFree();
        var startOfMonth = BrazilClock.currentYearMonth().atDay(1).atStartOfDay();
        return new SubscriptionUsageResponse(
                receiptRepository.countByUserIdAndSplitCopyFalseAndCreatedAtGreaterThanEqual(user.getId(), startOfMonth),
                freeCaps.getMonthlyReceipts(),
                customCategoryRepository.countByHouseholdId(householdId),
                freeCaps.getCustomCategories(),
                householdWatchedMarketCount(householdId),
                freeCaps.getWatchedMarkets(),
                receiptRepository.findOldestConfirmedIssuedAt(householdId).map(LocalDateTime::toLocalDate).orElse(null),
                freeCaps.getHistoryDays());
    }

    /** The watchlist is the household's, so the usage is its distinct markets. */
    private long householdWatchedMarketCount(UUID householdId) {
        return watchedMarketRepository.findAllByUserHouseholdId(householdId).stream()
                .map(UserWatchedMarket::getMarketCnpj)
                .distinct()
                .count();
    }
}
