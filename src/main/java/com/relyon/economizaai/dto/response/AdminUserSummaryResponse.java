package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight admin-facing snapshot of a user — what shows up in the
 * `GET /api/v1/admin/users` paginated list. Excludes home location and
 * other personal fields not relevant to triage.
 *
 * <p>{@code receiptCount}/{@code totalSpend} are the USER's OWN (receipts whose
 * {@code user_id} is this user). The household totals ({@code householdReceiptCount})
 * are kept separately and never conflated — the FE shows the "Domicílio N" badge
 * from {@code householdMemberCount} when it's &gt; 1.
 */
public record AdminUserSummaryResponse(
        UUID id,
        String name,
        String email,
        Role role,
        SubscriptionTier subscriptionTier,
        boolean emailVerified,
        boolean active,
        boolean excludedFromMetrics,
        boolean hasPushToken,
        UUID householdId,
        LocalDateTime createdAt,
        long receiptCount,
        BigDecimal totalSpend,
        long householdReceiptCount,
        long householdMemberCount
) {
    public static AdminUserSummaryResponse from(User user) {
        return from(user, 0L, BigDecimal.ZERO, 0L, 0L);
    }

    public static AdminUserSummaryResponse from(User user, long receiptCount, BigDecimal totalSpend,
                                                long householdReceiptCount, long householdMemberCount) {
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getSubscriptionTier(),
                user.isEmailVerified(),
                user.isActive(),
                user.isExcludedFromMetrics(),
                user.getPushDeviceToken() != null && !user.getPushDeviceToken().isBlank(),
                user.getHousehold() == null ? null : user.getHousehold().getId(),
                BrazilClock.fromUtc(user.getCreatedAt()),
                receiptCount,
                totalSpend == null ? BigDecimal.ZERO : totalSpend,
                householdReceiptCount,
                householdMemberCount
        );
    }
}
