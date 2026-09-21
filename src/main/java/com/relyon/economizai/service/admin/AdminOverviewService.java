package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.response.AdminOverviewResponse;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.SubscriptionStatus;
import com.relyon.economizai.repository.PriceObservationAuditRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.SubscriptionRepository;
import com.relyon.economizai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Read-only cross-area KPI snapshot for the admin home. Reuses the existing count
 * queries per area; user counts exclude admins + test accounts (includeInternal=false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final PriceObservationAuditRepository priceObservationAuditRepository;

    @Transactional(readOnly = true)
    public AdminOverviewResponse overview() {
        var today = LocalDate.now();
        var startOfToday = today.atStartOfDay();
        var weekAgo = today.minusDays(6).atStartOfDay();
        var monthAgo = today.minusDays(29).atStartOfDay();

        var usersTotal = userRepository.count();
        var usersToday = userRepository.countSignupsSince(startOfToday, false);
        var usersThisWeek = userRepository.countSignupsSince(weekAgo, false);
        var usersPro = userRepository.countProTier(false);
        var payingActive = subscriptionRepository.countPaying(SubscriptionStatus.ACTIVE, false);

        var receiptsTotal = receiptRepository.count();
        var receiptsToday = receiptRepository.countByCreatedAtGreaterThanEqual(startOfToday);
        var parseRate30d = parseRateSince(monthAgo);
        var activeHouseholds7d = receiptRepository.countActiveHouseholdsSince(weekAgo);
        var totalSpend = scale(receiptRepository.sumConfirmedTotal());

        var observations = priceObservationRepository.count();
        var contributingHouseholds = priceObservationAuditRepository.countDistinctContributingHouseholds();

        log.info("admin.overview users={} today={} receipts={} parse30d={} active7d={} spend={} obs={}",
                usersTotal, usersToday, receiptsTotal, parseRate30d, activeHouseholds7d, totalSpend, observations);
        return new AdminOverviewResponse(usersTotal, usersToday, usersThisWeek, usersPro, payingActive,
                receiptsTotal, receiptsToday, parseRate30d, activeHouseholds7d, totalSpend,
                observations, contributingHouseholds);
    }

    /** Parsed (confirmed/pending/rejected) ÷ (parsed + failed) over the window. */
    private double parseRateSince(LocalDateTime since) {
        var byStatus = new EnumMap<ReceiptStatus, Long>(ReceiptStatus.class);
        for (var row : receiptRepository.statusBreakdownSince(since)) {
            byStatus.put((ReceiptStatus) row[0], ((Number) row[1]).longValue());
        }
        var parsed = count(byStatus, ReceiptStatus.CONFIRMED)
                + count(byStatus, ReceiptStatus.PENDING_CONFIRMATION)
                + count(byStatus, ReceiptStatus.REJECTED);
        var failed = count(byStatus, ReceiptStatus.FAILED_PARSE);
        var denominator = parsed + failed;
        return denominator <= 0 ? 0d : BigDecimal.valueOf(parsed)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static long count(Map<ReceiptStatus, Long> byStatus, ReceiptStatus status) {
        return byStatus.getOrDefault(status, 0L);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
