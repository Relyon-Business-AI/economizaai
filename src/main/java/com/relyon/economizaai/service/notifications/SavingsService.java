package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.response.SavingsSummaryResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Reads back the Phase D north-star: total realized R$ savings attributable to
 * surfaced deals, scoped to the authenticated user's household. Sums the
 * {@code savingsAmount} column of {@code CONVERTED} telemetry events for every
 * user in the household (a digest could have gone to any household member).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsService {

    private final NotificationEventRepository eventRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public SavingsSummaryResponse summarize(User user) {
        var householdUserIds = userRepository.findAllByHouseholdId(user.getHousehold().getId()).stream()
                .map(householdUser -> householdUser.getId())
                .toList();
        if (householdUserIds.isEmpty()) {
            return new SavingsSummaryResponse(BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }

        var lifetime = eventRepository.sumConvertedSavings(householdUserIds, null);
        var last30 = eventRepository.sumConvertedSavings(
                householdUserIds, OffsetDateTime.now().minusDays(30));

        var total = zeroIfNull(lifetime.getTotalSavings());
        var recent = zeroIfNull(last30.getTotalSavings());
        var conversions = lifetime.getConversions();
        log.info("savings.summary household={} total=R${} conversions={}",
                user.getHousehold().getId(), total, conversions);
        return new SavingsSummaryResponse(total, conversions, recent);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
