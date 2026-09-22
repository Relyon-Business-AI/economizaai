package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight admin-facing snapshot of a user — what shows up in the
 * `GET /api/v1/admin/users` paginated list. Excludes home location and
 * other personal fields not relevant to triage.
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
        UUID householdId,
        LocalDateTime createdAt,
        long receiptCount,
        BigDecimal totalSpend
) {
    public static AdminUserSummaryResponse from(User user) {
        return from(user, 0L, BigDecimal.ZERO);
    }

    public static AdminUserSummaryResponse from(User user, long receiptCount) {
        return from(user, receiptCount, BigDecimal.ZERO);
    }

    public static AdminUserSummaryResponse from(User user, long receiptCount, BigDecimal totalSpend) {
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getSubscriptionTier(),
                user.isEmailVerified(),
                user.isActive(),
                user.isExcludedFromMetrics(),
                user.getHousehold() == null ? null : user.getHousehold().getId(),
                user.getCreatedAt(),
                receiptCount,
                totalSpend == null ? BigDecimal.ZERO : totalSpend
        );
    }
}
