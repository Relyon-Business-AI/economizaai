package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

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
        LocalDateTime createdAt,
        // Device the account was created on (immutable) and the one used on the
        // most recent login, plus when that last access happened — for ops triage.
        Platform registrationPlatform,
        Platform lastPlatform,
        OffsetDateTime lastAccessAt
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
                user.getCreatedAt(),
                user.getRegistrationPlatform(),
                user.getLastPlatform(),
                lastAccessAt(user)
        );
    }

    /** Most recent of the per-platform login timestamps, or null if never logged with a known platform. */
    private static OffsetDateTime lastAccessAt(User user) {
        return Stream.of(user.getLastWebLoginAt(), user.getLastAndroidLoginAt(), user.getLastIosLoginAt())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
