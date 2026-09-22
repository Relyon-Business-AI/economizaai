package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        Role role,
        SubscriptionTier subscriptionTier,
        boolean contributionOptIn,
        boolean emailVerified,
        LocalDateTime emailVerifiedAt,
        BigDecimal homeLatitude,
        BigDecimal homeLongitude,
        Platform registrationPlatform,
        Platform lastPlatform,
        OffsetDateTime lastWebLoginAt,
        OffsetDateTime lastAndroidLoginAt,
        OffsetDateTime lastIosLoginAt,
        LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getSubscriptionTier(),
                user.isContributionOptIn(),
                user.isEmailVerified(),
                user.getEmailVerifiedAt(),
                user.getHomeLatitude(),
                user.getHomeLongitude(),
                user.getRegistrationPlatform(),
                user.getLastPlatform(),
                user.getLastWebLoginAt(),
                user.getLastAndroidLoginAt(),
                user.getLastIosLoginAt(),
                user.getCreatedAt()
        );
    }
}
