package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin-facing detail view of a single user. Includes household stats,
 * receipt counts by status, and a 30-day spend snapshot — the things you
 * need when triaging "this user reported X is wrong".
 */
public record AdminUserDetailResponse(
        UUID id,
        String name,
        String email,
        Role role,
        SubscriptionTier subscriptionTier,
        boolean emailVerified,
        boolean active,
        boolean contributionOptIn,
        boolean excludedFromMetrics,
        UUID householdId,
        long householdMemberCount,
        ReceiptCounts receipts,
        BigDecimal spendLast30Days,
        LocalDateTime createdAt
) {
    public record ReceiptCounts(long pendingConfirmation, long confirmed, long rejected, long failedParse) {}

    public static AdminUserDetailResponse from(User user,
                                               long householdMemberCount,
                                               ReceiptCounts receipts,
                                               BigDecimal spendLast30Days) {
        return new AdminUserDetailResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getSubscriptionTier(),
                user.isEmailVerified(),
                user.isActive(),
                user.isContributionOptIn(),
                user.isExcludedFromMetrics(),
                user.getHousehold() == null ? null : user.getHousehold().getId(),
                householdMemberCount,
                receipts,
                spendLast30Days == null ? BigDecimal.ZERO : spendLast30Days,
                user.getCreatedAt()
        );
    }
}
